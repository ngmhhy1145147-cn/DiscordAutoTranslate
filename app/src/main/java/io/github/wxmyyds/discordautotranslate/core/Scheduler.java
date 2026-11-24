package io.github.wxmyyds.discordautotranslate.core;

import androidx.annotation.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * FIFO translation scheduling with bounded pending work and main-thread result delivery.
 * Pending tasks never occupy workers; deduplication covers pending, running and undelivered results.
 */
public final class Scheduler {

    /** Implementations must return promptly when interrupted or cancelled. */
    public interface Engine {
        String translate(String text) throws Exception;

        /** Cancel the request currently bound to this worker. */
        default void cancel(Thread worker) {
            worker.interrupt();
        }
    }

    public interface BatchEngine {
        List<String> translateBatch(List<String> texts) throws Exception;

        default void cancel(Thread worker) {
            worker.interrupt();
        }
    }

    public static final class BatchItem {
        public final TranslationTaskId id;
        public final String text;

        public BatchItem(TranslationTaskId id, String text) {
            this.id = Objects.requireNonNull(id, "id");
            this.text = Objects.requireNonNull(text, "text");
        }
    }

    /** Requested items and successful translations have identical order and cardinality. */
    public interface BatchCallback {
        void onResult(List<BatchItem> requested, boolean accepted,
                      @Nullable List<String> translated, @Nullable Throwable error);
    }

    /** Results, including explicit admission failures, are delivered on the main thread. */
    public interface Callback {
        void onResult(TranslationTaskId taskId, boolean accepted,
                      @Nullable String translated, @Nullable Throwable error);
    }

    public interface Acceptance {
        boolean isStillValid(TranslationTaskId taskId);
    }

    private static final int POOL_MAX = 8;
    private static final int PENDING_CAPACITY = 512;

    private static volatile Scheduler sInstance;
    /** Retired instances stay reachable until termination has actually been awaited successfully. */
    private static final List<Scheduler> RETIRED = new ArrayList<>();

    public static Scheduler get() {
        Scheduler s = sInstance;
        if (s == null) {
            synchronized (Scheduler.class) {
                s = sInstance;
                if (s == null) {
                    s = new Scheduler();
                    sInstance = s;
                }
            }
        }
        return s;
    }

    private static Scheduler retireCurrentLocked() {
        Scheduler instance = sInstance;
        if (instance != null) {
            RETIRED.add(instance);
            sInstance = null;
        }
        return instance;
    }

    /** Request shutdown, retaining the retired instance for a subsequent termination check. */
    public static void reset() {
        Scheduler instance;
        synchronized (Scheduler.class) {
            instance = retireCurrentLocked();
        }
        // Do not acquire the scheduler lock while holding the singleton lock: callbacks can reenter get().
        if (instance != null) instance.shutdown();
    }

    /**
     * Stop all retired/current instances within one timeout budget. A timeout never forgets live workers,
     * even if reset() or get() occurred between two calls.
     */
    public static boolean resetAndAwait(long timeoutMs) {
        List<Scheduler> instances;
        synchronized (Scheduler.class) {
            retireCurrentLocked();
            instances = new ArrayList<>(RETIRED);
        }
        long budget = TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMs));
        long start = System.nanoTime();
        // Request every shutdown before spending the budget awaiting any one instance.
        for (Scheduler instance : instances) instance.shutdown();
        boolean terminated = true;
        for (Scheduler instance : instances) {
            long remaining = Math.max(0L, budget - (System.nanoTime() - start));
            if (instance.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
                synchronized (Scheduler.class) {
                    RETIRED.remove(instance);
                }
            } else {
                terminated = false;
            }
        }
        return terminated;
    }

    /**
     * Only reserved active slots are sent to this dedicated executor. Its internal handoff queue can
     * contain at most eight reservations; the user-facing pending FIFO lives exclusively in Scheduler.
     */
    private final ThreadPoolExecutor pool = new ThreadPoolExecutor(
            POOL_MAX, POOL_MAX, 30L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(POOL_MAX),
            r -> {
                Thread t = new Thread(r, "dat-translate");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy());

    /** The only lock for admission, dispatch, delivery reservation and shutdown; never for callbacks. */
    private final Object lifecycleLock = new Object();
    private final ArrayDeque<Task<?>> pending = new ArrayDeque<>();
    private final Map<Object, Task<?>> inFlight = new HashMap<>();
    /** Independent of the version map: an obsolete running attempt still owns an active slot. */
    private final Set<Task<?>> activeTasks = new HashSet<>();
    private int configuredConcurrency = Math.max(1, Math.min(POOL_MAX, Config.DEF_MAX_CONCURRENCY));
    /** Includes executor reservations, not callbacks or scheduler-pending work. */
    private int activeCount;
    private volatile boolean shutdown;
    /** Shutdown invalidates queued deliveries; a delivery reserved before it may run outside the lock. */
    private long deliveryEpoch;

    private final AtomicInteger completed = new AtomicInteger();
    private final AtomicInteger discarded = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();

    /** Deduplication deliberately ignores attempt identity, unlike TranslationTaskId.equals(). */
    private static final class TaskKey {
        final String messageId;
        final String contentHash;
        final int generation;

        TaskKey(TranslationTaskId id) {
            messageId = id.messageId;
            contentHash = id.contentHash;
            generation = id.generation;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof TaskKey)) return false;
            TaskKey that = (TaskKey) other;
            return generation == that.generation && messageId.equals(that.messageId)
                    && contentHash.equals(that.contentHash);
        }

        @Override
        public int hashCode() {
            int result = messageId.hashCode();
            result = 31 * result + contentHash.hashCode();
            return 31 * result + generation;
        }
    }

    private interface Work<T> {
        T translate() throws Exception;
    }

    private interface Cancellation {
        void cancel(Thread worker);
    }

    private interface ResultCallback<T> {
        void onResult(boolean accepted, @Nullable T value, @Nullable Throwable error);
    }

    private final class Task<T> implements Runnable {
        final Object key;
        final Work<T> work;
        final Cancellation cancellation;
        final BooleanSupplier acceptance;
        final ResultCallback<T> callback;
        /** Accessed only under lifecycleLock. */
        Thread worker;
        boolean delivered;
        volatile boolean obsolete;

        Task(Object key, Work<T> work, Cancellation cancellation, BooleanSupplier acceptance,
             ResultCallback<T> callback) {
            this.key = key;
            this.work = work;
            this.cancellation = cancellation;
            this.acceptance = acceptance;
            this.callback = callback;
        }

        @Override
        public void run() {
            runTask(this);
        }
    }

    public void shutdown() {
        Map<Thread, Cancellation> workers = new HashMap<>();
        synchronized (lifecycleLock) {
            if (shutdown) return;
            shutdown = true;
            deliveryEpoch++;
            for (Task<?> task : activeTasks) {
                if (task.worker != null) workers.put(task.worker, task.cancellation);
            }
            pending.clear();
            inFlight.clear();
            // Tasks removed from the executor never enter runTask and must release their reservations here.
            for (Runnable queued : pool.shutdownNow()) {
                if (activeTasks.remove(queued)) activeCount--;
            }
        }
        // Cancellation may involve engine locks or external code; never hold the scheduler lock here.
        for (Map.Entry<Thread, Cancellation> entry : workers.entrySet()) {
            try {
                entry.getValue().cancel(entry.getKey());
            } catch (Throwable ignored) {
                // All workers were already interrupted by shutdownNow; continue cancelling the others.
            }
        }
    }

    public boolean shutdownAndAwait(long timeoutMs) {
        shutdown();
        return awaitTermination(Math.max(0L, timeoutMs), TimeUnit.MILLISECONDS);
    }

    private boolean awaitTermination(long timeout, TimeUnit unit) {
        try {
            return pool.awaitTermination(timeout, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Raising starts pending work immediately; lowering never interrupts already reserved/running work. */
    public void setMaxConcurrency(int value) {
        List<Runnable> rejectedResults = new ArrayList<>();
        synchronized (lifecycleLock) {
            configuredConcurrency = Math.max(1, Math.min(POOL_MAX, value));
            dispatchLocked(rejectedResults);
        }
        for (Runnable result : rejectedResults) result.run();
    }

    public int completedCount() {
        return completed.get();
    }

    public int discardedCount() {
        return discarded.get();
    }

    public int failedCount() {
        return failed.get();
    }

    public String stats() {
        synchronized (lifecycleLock) {
            return "scheduler[completed=" + completed.get()
                    + ", discarded=" + discarded.get()
                    + ", failed=" + failed.get()
                    + ", inFlight=" + inFlight.size()
                    + ", active=" + activeCount
                    + ", pending=" + pending.size()
                    + ", concurrency=" + configuredConcurrency + "]";
        }
    }

    /**
     * Admit one version of a row, deduping only while the existing attempt remains valid.
     * Identity is created by the state owner, never by Scheduler. Obsolete pending rows are pruned
     * before capacity rejection. A still-full FIFO reports an explicit failure, with no caller-runs
     * or alternate execution path.
     */
    public void submit(TranslationTaskId taskId, String text, Engine engine,
                       Acceptance accept, Callback callback) {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(accept, "accept");
        Objects.requireNonNull(callback, "callback");
        enqueue(new Task<>(new TaskKey(taskId), () -> engine.translate(text), engine::cancel,
                () -> accept.isStillValid(taskId),
                (accepted, value, error) -> callback.onResult(taskId, accepted, value, error)));
    }

    public void submitBatch(List<BatchItem> items, BatchEngine engine,
                            Acceptance accept, BatchCallback callback) {
        List<BatchItem> batch = Collections.unmodifiableList(new ArrayList<>(items));
        if (batch.isEmpty()) throw new IllegalArgumentException("Empty translation batch");
        List<TranslationTaskId> key = new ArrayList<>(batch.size());
        for (BatchItem item : batch) key.add(item.id);
        if (new HashSet<>(key).size() != key.size()) {
            throw new IllegalArgumentException("Duplicate translation batch member");
        }
        AtomicReference<List<BatchItem>> requested = new AtomicReference<>(batch);
        enqueue(new Task<List<String>>(key, () -> {
            List<BatchItem> current = new ArrayList<>();
            List<String> texts = new ArrayList<>();
            for (BatchItem item : batch) {
                if (accept.isStillValid(item.id)) {
                    current.add(item);
                    texts.add(item.text);
                }
            }
            requested.set(Collections.unmodifiableList(current));
            if (current.isEmpty()) {
                throw new TranslationException(TranslationException.Kind.CANCELLED, "任务已失效");
            }
            List<String> values = engine.translateBatch(Collections.unmodifiableList(texts));
            if (values == null || values.size() != current.size()) {
                throw new TranslationException(TranslationException.Kind.PARSE,
                        TranslationException.Reason.TRANSLATION_INVALID, "译文与批次消息数不一致");
            }
            return Collections.unmodifiableList(new ArrayList<>(values));
        }, engine::cancel, () -> {
            for (BatchItem item : batch) {
                if (accept.isStillValid(item.id)) return true;
            }
            return false;
        }, (accepted, translated, error) -> callback.onResult(
                requested.get(), accepted, translated, error)));
    }

    private void enqueue(Task<?> task) {
        boolean queueFull;
        List<Task<?>> obsoletePending = new ArrayList<>();
        List<Runnable> rejectedResults = new ArrayList<>();
        synchronized (lifecycleLock) {
            if (shutdown) return;
            Task<?> existing = inFlight.get(task.key);
            if (existing != null) {
                if (isStillValid(existing)) return;
                if (shutdown) return;
                // An evicted/recreated row can have the same version but a different owning attempt.
                // Remove obsolete waiting work rather than strand the new state behind silent dedup.
                existing.obsolete = true;
                if (pending.remove(existing)) obsoletePending.add(existing);
            }
            if (pending.size() == PENDING_CAPACITY) {
                // LRU eviction can invalidate unrelated waiting rows. Reclaim those slots before
                // treating the newest binding as overflow; valid pending work is never displaced.
                for (Task<?> waiting : new ArrayList<>(pending)) {
                    if (!isStillValid(waiting) && pending.remove(waiting)) {
                        waiting.obsolete = true;
                        obsoletePending.add(waiting);
                    }
                    if (shutdown) return;
                }
            }
            inFlight.put(task.key, task);
            queueFull = pending.size() == PENDING_CAPACITY;
            if (!queueFull) {
                pending.addLast(task);
                dispatchLocked(rejectedResults);
            }
        }
        for (Runnable result : rejectedResults) result.run();
        for (Task<?> obsolete : obsoletePending) {
            postResult(obsolete, null, new TranslationException(
                    TranslationException.Kind.CANCELLED, "任务已失效"));
        }
        if (queueFull) {
            postResult(task, null, new TranslationException(
                    TranslationException.Kind.UNKNOWN, "翻译等待队列已满（512）"));
        }
    }

    /**
     * Must be called under lifecycleLock. Only dispatched tasks consume active slots. Rejection
     * deliveries are collected for posting after unlocking, including with a synchronous dispatcher.
     */
    private void dispatchLocked(List<Runnable> rejectedResults) {
        while (!shutdown && activeCount < configuredConcurrency && !pending.isEmpty()) {
            Task<?> task = pending.removeFirst();
            activeCount++;
            activeTasks.add(task);
            try {
                pool.execute(task);
            } catch (RejectedExecutionException e) {
                activeCount--;
                activeTasks.remove(task);
                rejectedResults.add(() -> postResult(task, null, new TranslationException(
                        TranslationException.Kind.UNKNOWN, "翻译执行器拒绝任务", e)));
            }
        }
    }

    private <T> void runTask(Task<T> task) {
        synchronized (lifecycleLock) {
            if (shutdown) {
                activeCount--;
                activeTasks.remove(task);
                return;
            }
            task.worker = Thread.currentThread();
        }
        T result = null;
        Throwable error = null;
        List<Runnable> rejectedResults = new ArrayList<>();
        try {
            if (shutdown || Thread.currentThread().isInterrupted()) {
                throw new TranslationException(TranslationException.Kind.CANCELLED, "任务已取消");
            }
            if (!isStillValid(task)) {
                // Execution rejection is terminal even if settings/ownership become valid before UI delivery.
                task.obsolete = true;
                throw new TranslationException(TranslationException.Kind.CANCELLED, "任务已失效");
            }
            if (shutdown || Thread.currentThread().isInterrupted()) {
                throw new TranslationException(TranslationException.Kind.CANCELLED, "任务已取消");
            }
            result = task.work.translate();
        } catch (Throwable t) {
            error = t;
        } finally {
            synchronized (lifecycleLock) {
                task.worker = null;
                activeCount--;
                activeTasks.remove(task);
                dispatchLocked(rejectedResults);
            }
        }
        for (Runnable rejected : rejectedResults) rejected.run();
        postResult(task, result, error);
    }

    /**
     * The final epoch check and counter update reserve delivery (its lifecycle start) under the lock.
     * Shutdown suppresses every later reservation. An already reserved callback may physically enter
     * or return after shutdown; shutdown neither waits for it nor revokes it. This lets main-thread
     * callbacks reenter shutdownAndAwait/resetAndAwait without blocking workers' final slot release.
     * Keep the dedup marker until the external callback returns, including when it throws or reenters.
     */
    private <T> void postResult(Task<T> task, @Nullable T translated, @Nullable Throwable error) {
        final long epoch;
        synchronized (lifecycleLock) {
            if (shutdown) return;
            epoch = deliveryEpoch;
        }
        MainThread.post(() -> {
            final boolean accepted;
            synchronized (lifecycleLock) {
                if (shutdown || epoch != deliveryEpoch || task.delivered) return;
                task.delivered = true;
                accepted = inFlight.get(task.key) == task && isStillValid(task);
                // Acceptance may reenter shutdown; no reservation or counters may survive that boundary.
                if (shutdown || epoch != deliveryEpoch) {
                    inFlight.remove(task.key, task);
                    return;
                }
                if (!accepted) {
                    discarded.incrementAndGet();
                } else if (error != null) {
                    failed.incrementAndGet();
                } else {
                    completed.incrementAndGet();
                }
            }
            try {
                task.callback.onResult(accepted, translated, error);
            } finally {
                synchronized (lifecycleLock) {
                    inFlight.remove(task.key, task);
                }
            }
        });
    }

    private boolean isStillValid(Task<?> task) {
        try {
            return !task.obsolete && task.acceptance.getAsBoolean();
        } catch (Throwable t) {
            return false;
        }
    }

    public static String contentHash(String text, String configVersion) {
        return Sha256.digestHex(text, configVersion);
    }

    /** 主线程投递工具。Discord 消息渲染全部发生在主线程。 */
    public static final class MainThread {
        interface Dispatcher {
            boolean post(Runnable runnable);
        }

        private static final Object LOCK = new Object();
        private static volatile android.os.Handler sHandler;
        private static volatile Dispatcher sTestDispatcher;
        private static long sEpoch;

        private MainThread() {
        }

        public static void install() {
            synchronized (LOCK) {
                if (sHandler == null) {
                    sHandler = new android.os.Handler(android.os.Looper.getMainLooper());
                }
                sTestDispatcher = null;
                sEpoch++;
            }
        }

        public static void clear() {
            android.os.Handler old;
            synchronized (LOCK) {
                old = sHandler;
                sHandler = null;
                sTestDispatcher = null;
                sEpoch++;
            }
            if (old != null) {
                old.removeCallbacksAndMessages(null);
            }
        }

        /** 仅供同包 JVM 单测显式启用；生产环境没有 direct-dispatch fallback。 */
        static void setDirectDispatcherForTests() {
            setDispatcherForTests(runnable -> {
                runnable.run();
                return true;
            });
        }

        static void setDispatcherForTests(Dispatcher dispatcher) {
            android.os.Handler old;
            synchronized (LOCK) {
                old = sHandler;
                sHandler = null;
                sTestDispatcher = dispatcher;
                sEpoch++;
            }
            if (old != null) {
                old.removeCallbacksAndMessages(null);
            }
        }

        /**
         * Complete owned UI work before returning. Timeout cancels work that has not started;
         * an already running action may finish later, so false must reject lifecycle completion.
         * Runnable failures are rethrown on the awaiting caller. No dispatcher is created here.
         */
        public static boolean runAndAwait(Runnable runnable, long timeoutMs) {
            Objects.requireNonNull(runnable, "runnable");
            final android.os.Handler handler;
            final Dispatcher dispatcher;
            final long epoch;
            synchronized (LOCK) {
                handler = sHandler;
                dispatcher = sTestDispatcher;
                epoch = sEpoch;
            }
            if (handler == null && dispatcher == null) return false;

            CountDownLatch completed = new CountDownLatch(1);
            // Pending -> running -> done, or pending -> cancelled. Never revoke running UI work.
            AtomicInteger state = new AtomicInteger(0);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Runnable guarded = () -> {
                synchronized (LOCK) {
                    if (epoch != sEpoch || (handler != null && handler != sHandler)
                            || (dispatcher != null && dispatcher != sTestDispatcher)) {
                        if (state.compareAndSet(0, 3)) completed.countDown();
                        return;
                    }
                    if (!state.compareAndSet(0, 1)) return;
                }
                try {
                    runnable.run();
                } catch (Throwable thrown) {
                    failure.set(thrown);
                } finally {
                    state.set(2);
                    completed.countDown();
                }
            };
            boolean posted;
            if (dispatcher != null) {
                posted = dispatcher.post(guarded);
            } else if (android.os.Looper.myLooper() == handler.getLooper()) {
                guarded.run();
                posted = true;
            } else {
                posted = handler.post(guarded);
            }
            if (!posted) {
                state.compareAndSet(0, 3);
                if (handler != null) handler.removeCallbacks(guarded);
                return false;
            }
            boolean finished = completed.getCount() == 0L;
            if (!finished) {
                try {
                    finished = completed.await(Math.max(0L, timeoutMs), TimeUnit.MILLISECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            if (!finished) {
                state.compareAndSet(0, 3);
                if (handler != null) handler.removeCallbacks(guarded);
                return false;
            }
            Throwable thrown = failure.get();
            if (thrown instanceof RuntimeException) throw (RuntimeException) thrown;
            if (thrown instanceof Error) throw (Error) thrown;
            if (thrown != null) throw new IllegalStateException("Main-thread action failed", thrown);
            return state.get() == 2;
        }

        public static void post(Runnable runnable) {
            postDelayed(runnable, 0L);
        }

        public static boolean postDelayed(Runnable runnable, long delayMs) {
            final android.os.Handler handler;
            final Dispatcher dispatcher;
            final long epoch;
            synchronized (LOCK) {
                handler = sHandler;
                dispatcher = sTestDispatcher;
                epoch = sEpoch;
            }
            if (handler == null && dispatcher == null) return false;

            Runnable guarded = () -> {
                synchronized (LOCK) {
                    if (epoch != sEpoch || (handler != null && handler != sHandler)
                            || (dispatcher != null && dispatcher != sTestDispatcher)) {
                        return;
                    }
                }
                runnable.run();
            };
            if (dispatcher != null) {
                return dispatcher.post(guarded);
            }
            if (delayMs == 0L && android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                guarded.run();
                return true;
            }
            return handler.postDelayed(guarded, delayMs);
        }
    }
}
