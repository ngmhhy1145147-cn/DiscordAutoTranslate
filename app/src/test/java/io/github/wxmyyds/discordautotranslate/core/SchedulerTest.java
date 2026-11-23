package io.github.wxmyyds.discordautotranslate.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Scheduler admission, dispatch, identity and delivery lifecycle. No production direct-dispatch fallback. */
public class SchedulerTest {

    @Before
    public void installTestDispatcher() {
        Scheduler.MainThread.clear();
        Scheduler.MainThread.setDirectDispatcherForTests();
    }

    @After
    public void clearTestDispatcher() {
        Scheduler.MainThread.clear();
    }

    private static TranslationTaskId id(String messageId, String hash) {
        return TranslationTaskId.create(messageId, hash, 7);
    }

    /** Test-only convenience; the production API accepts only an existing full task identity. */
    private static void submit(Scheduler scheduler, String messageId, String hash, String text,
                               Scheduler.Engine engine, Scheduler.Acceptance accept,
                               Scheduler.Callback callback) {
        scheduler.submit(id(messageId, hash), text, engine, accept, callback);
    }

    private static final class CountingEngine implements Scheduler.Engine {
        final AtomicInteger calls = new AtomicInteger();
        final String result;

        CountingEngine(String result) {
            this.result = result;
        }

        @Override
        public String translate(String text, String precedingContext) {
            calls.incrementAndGet();
            return result;
        }
    }

    private static final Scheduler.Acceptance ALWAYS = taskId -> true;

    private static final class Collector implements Scheduler.Callback {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile TranslationTaskId taskId;
        volatile boolean accepted;
        volatile String translated;
        volatile Throwable error;

        @Override
        public void onResult(TranslationTaskId taskId, boolean accepted, String translated, Throwable error) {
            this.taskId = taskId;
            this.accepted = accepted;
            this.translated = translated;
            this.error = error;
            latch.countDown();
        }

        void await() throws InterruptedException {
            assertTrue("callback not invoked in time", latch.await(10, TimeUnit.SECONDS));
        }
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue("engine did not enter in time", latch.await(5, TimeUnit.SECONDS));
    }

    private static Runnable take(BlockingQueue<Runnable> deliveries) throws InterruptedException {
        Runnable delivery = deliveries.poll(5, TimeUnit.SECONDS);
        assertNotNull("result was not posted", delivery);
        return delivery;
    }

    private static void installQueuedDispatcher(BlockingQueue<Runnable> deliveries) {
        Scheduler.MainThread.setDispatcherForTests(runnable -> {
            deliveries.add(runnable);
            return true;
        });
    }

    @Test
    public void mainThreadAwaitRunsWithDirectDispatcherAndPropagatesFailure() {
        AtomicInteger calls = new AtomicInteger();
        assertTrue(Scheduler.MainThread.runAndAwait(calls::incrementAndGet, 0L));
        assertEquals(1, calls.get());
        RuntimeException failure = new RuntimeException("UI cleanup failed");
        try {
            Scheduler.MainThread.runAndAwait(() -> { throw failure; }, 1000L);
            throw new AssertionError("UI failure was swallowed");
        } catch (RuntimeException thrown) {
            assertSame(failure, thrown);
        }
    }

    @Test
    public void mainThreadAwaitRejectsMissingOrRejectingDispatcher() {
        AtomicInteger calls = new AtomicInteger();
        Scheduler.MainThread.clear();
        assertFalse(Scheduler.MainThread.runAndAwait(calls::incrementAndGet, 1000L));
        Scheduler.MainThread.setDispatcherForTests(runnable -> false);
        assertFalse(Scheduler.MainThread.runAndAwait(calls::incrementAndGet, 1000L));
        assertEquals(0, calls.get());
    }

    @Test
    public void mainThreadAwaitWaitsForQueuedActionToComplete() throws Exception {
        BlockingQueue<Runnable> deliveries = new LinkedBlockingQueue<>();
        installQueuedDispatcher(deliveries);
        AtomicBoolean completed = new AtomicBoolean();
        AtomicBoolean result = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch returned = new CountDownLatch(1);
        Thread caller = new Thread(() -> {
            try {
                result.set(Scheduler.MainThread.runAndAwait(() -> completed.set(true), 2000L));
            } catch (Throwable thrown) {
                failure.set(thrown);
            } finally {
                returned.countDown();
            }
        });
        caller.start();
        Runnable action = take(deliveries);
        try {
            assertFalse(completed.get());
            assertFalse(returned.await(50L, TimeUnit.MILLISECONDS));
            action.run();
            assertTrue(returned.await(5L, TimeUnit.SECONDS));
            assertTrue(completed.get());
            assertTrue(result.get());
            assertNull(failure.get());
        } finally {
            action.run();
            caller.join(5000L);
        }
        assertFalse(caller.isAlive());
    }

    @Test
    public void mainThreadAwaitPropagatesQueuedActionFailureToCaller() throws Exception {
        BlockingQueue<Runnable> deliveries = new LinkedBlockingQueue<>();
        installQueuedDispatcher(deliveries);
        Error failure = new AssertionError("queued cleanup failure");
        AtomicReference<Throwable> observed = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                Scheduler.MainThread.runAndAwait(() -> { throw failure; }, 2000L);
            } catch (Throwable thrown) {
                observed.set(thrown);
            }
        });
        caller.start();
        try {
            take(deliveries).run();
        } finally {
            caller.join(5000L);
        }
        assertFalse(caller.isAlive());
        assertSame(failure, observed.get());
    }

    @Test
    public void mainThreadAwaitTimeoutCancelsQueuedAction() throws Exception {
        BlockingQueue<Runnable> deliveries = new LinkedBlockingQueue<>();
        installQueuedDispatcher(deliveries);
        AtomicInteger calls = new AtomicInteger();
        long start = System.nanoTime();
        assertFalse(Scheduler.MainThread.runAndAwait(calls::incrementAndGet, 20L));
        assertTrue("UI wait was not bounded", System.nanoTime() - start
                < TimeUnit.SECONDS.toNanos(2L));
        take(deliveries).run();
        assertEquals(0, calls.get());
    }

    @Test
    public void mainThreadAwaitRejectsQueuedActionFromClearedDispatcher() throws Exception {
        BlockingQueue<Runnable> deliveries = new LinkedBlockingQueue<>();
        installQueuedDispatcher(deliveries);
        AtomicInteger calls = new AtomicInteger();
        AtomicBoolean result = new AtomicBoolean(true);
        Thread caller = new Thread(() -> result.set(
                Scheduler.MainThread.runAndAwait(calls::incrementAndGet, 2000L)));
        caller.start();
        try {
            Runnable action = take(deliveries);
            Scheduler.MainThread.clear();
            Scheduler.MainThread.setDirectDispatcherForTests();
            action.run();
        } finally {
            caller.join(5000L);
        }
        assertFalse(caller.isAlive());
        assertFalse(result.get());
        assertEquals(0, calls.get());
    }

    @Test
    public void mainThreadAwaitInterruptionCancelsQueuedActionAndPreservesInterrupt() throws Exception {
        BlockingQueue<Runnable> deliveries = new LinkedBlockingQueue<>();
        installQueuedDispatcher(deliveries);
        AtomicInteger calls = new AtomicInteger();
        Thread.currentThread().interrupt();
        try {
            assertFalse(Scheduler.MainThread.runAndAwait(calls::incrementAndGet, 2000L));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        take(deliveries).run();
        assertEquals(0, calls.get());
    }

    @Test
    public void successPathDeliversTranslation() throws Exception {
        Scheduler s = new Scheduler();
        try {
            CountingEngine engine = new CountingEngine("译文");
            Collector c = new Collector();
            submit(s, "1", "h1", "hello", engine, ALWAYS, c);
            c.await();
            assertTrue(c.accepted);
            assertEquals("译文", c.translated);
            assertNull(c.error);
            assertEquals(1, engine.calls.get());
            assertEquals(1, s.completedCount());
        } finally {
            s.shutdown();
        }
    }

    @Test
    public void duplicateInFlightIsDeduped() throws Exception {
        Scheduler s = new Scheduler();
        CountDownLatch release = new CountDownLatch(1);
        try {
            AtomicInteger calls = new AtomicInteger();
            Scheduler.Engine blocking = (text, precedingContext) -> {
                calls.incrementAndGet();
                release.await(5, TimeUnit.SECONDS);
                return "t";
            };
            TranslationTaskId first = id("1", "h1");
            TranslationTaskId newerAttempt = id("1", "h1");
            Collector c = new Collector();
            AtomicInteger duplicates = new AtomicInteger();
            Scheduler.Callback duplicate = (taskId, accepted, translated, error) -> duplicates.incrementAndGet();
            s.submit(first, "hello", blocking, ALWAYS, c);
            // A newer (or later-resubmitted older) attempt of the same row/hash/generation is deduped.
            s.submit(newerAttempt, "hello", blocking, ALWAYS, duplicate);
            s.submit(first, "hello", blocking, ALWAYS, duplicate);
            release.countDown();
            c.await();
            assertNotEquals(first, newerAttempt);
            assertSame(first, c.taskId);
            assertEquals(1, calls.get());
            assertEquals(0, duplicates.get());
        } finally {
            release.countDown();
            s.shutdown();
        }
    }

    @Test
    public void dedupPersistsUntilCallbackReturnsThenAllowsNewAttempt() throws Exception {
        BlockingQueue<Runnable> deliveries = new LinkedBlockingQueue<>();
        installQueuedDispatcher(deliveries);
        Scheduler s = new Scheduler();
        try {
            CountingEngine engine = new CountingEngine("t");
            TranslationTaskId first = id("1", "h1");
            TranslationTaskId next = id("1", "h1");
            Collector duplicate = new Collector();
            Collector original = new Collector();
            s.submit(first, "hello", engine, ALWAYS, (taskId, accepted, translated, error) -> {
                s.submit(next, "hello", engine, ALWAYS, duplicate);
                original.onResult(taskId, accepted, translated, error);
            });
            Runnable delivered = take(deliveries);
            assertTrue(s.stats(), s.stats().contains("active=0"));
            assertTrue(s.stats(), s.stats().contains("inFlight=1"));
            // Engine finished but UI has not run: neither older nor newer attempts may bypass dedup.
            s.submit(next, "hello", engine, ALWAYS, duplicate);
            s.submit(first, "hello", engine, ALWAYS, duplicate);
            delivered.run();
            original.await();
            assertEquals(1, engine.calls.get());
            assertTrue(deliveries.isEmpty());
            assertEquals(1L, duplicate.latch.getCount());
            assertTrue(s.stats(), s.stats().contains("inFlight=0"));

            s.submit(next, "hello", engine, ALWAYS, duplicate);
            take(deliveries).run();
            duplicate.await();
            assertSame(next, duplicate.taskId);
            assertEquals(2, engine.calls.get());
        } finally {
            s.shutdown();
        }
    }

    @Test
    public void callbackThrowStillRemovesDedupMarker() throws Exception {
        BlockingQueue<Runnable> deliveries = new LinkedBlockingQueue<>();
        installQueuedDispatcher(deliveries);
        Scheduler s = new Scheduler();
        try {
            CountingEngine engine = new CountingEngine("t");
            RuntimeException failure = new RuntimeException("callback failure");
            submit(s, "1", "h1", "hello", engine, ALWAYS, (taskId, accepted, translated, error) -> {
                throw failure;
            });
            try {
                take(deliveries).run();
                throw new AssertionError("callback exception was swallowed");
            } catch (RuntimeException e) {
                assertSame(failure, e);
            }
            assertTrue(s.stats(), s.stats().contains("inFlight=0"));
            Collector c = new Collector();
            submit(s, "1", "h1", "hello", engine, ALWAYS, c);
            take(deliveries).run();
            c.await();
            assertEquals(2, engine.calls.get());
        } finally {
            s.shutdown();
        }
    }

    @Test
    public void differentMessageIdsAreNotDeduped() throws Exception {
        Scheduler s = new Scheduler();
        try {
            CountingEngine engine = new CountingEngine("t");
            Collector c1 = new Collector();
            Collector c2 = new Collector();
            submit(s, "1", "h1", "a", engine, ALWAYS, c1);
            submit(s, "2", "h2", "b", engine, ALWAYS, c2);
            c1.await();
            c2.await();
            assertEquals(2, engine.calls.get());
        } finally {
            s.shutdown();
        }
    }

    @Test
    public void staleResultIsDiscardedWhenAcceptanceRejects() throws Exception {
        Scheduler s = new Scheduler();
        try {
            CountingEngine engine = new CountingEngine("译文");
            Collector c = new Collector();
            submit(s, "1", "h1", "hello", engine, taskId -> false, c);
            c.await();
            assertFalse(c.accepted);
            assertEquals(0, s.completedCount());
            assertEquals(1, s.discardedCount());
        } finally {
            s.shutdown();
        }
    }

    @Test
    public void staleResultIsNotEvenRequested() throws Exception {
        Scheduler s = new Scheduler();
        try {
            CountingEngine engine = new CountingEngine("译文");
            Collector c = new Collector();
            submit(s, "1", "h1", "hello", engine, taskId -> false, c);
            c.await();
            assertEquals(0, engine.calls.get());
            assertFalse(c.accepted);
        } finally {
            s.shutdown();
        }
    }

    @Test
    public void staleAtMainThreadDeliveryIsDiscarded() throws Exception {
        BlockingQueue<Runnable> deliveries = new LinkedBlockingQueue<>();
        installQueuedDispatcher(deliveries);
        Scheduler s = new Scheduler();
        try {
            AtomicBoolean valid = new AtomicBoolean(true);
            Collector c = new Collector();
            submit(s, "1", "h1", "hello", new CountingEngine("t"), taskId -> valid.get(), c);
            Runnable delivery = take(deliveries);
            valid.set(false);
            delivery.run();
            c.await();
            assertFalse(c.accepted);
            assertEquals(1, s.discardedCount());
            assertEquals(0, s.completedCount());
            assertTrue(s.stats(), s.stats().contains("inFlight=0"));
        } finally {
            s.shutdown();
        }
    }

    @Test
    public void disabledPendingTaskStaysDiscardedAfterReenableBeforeUiDelivery() throws Exception {
        BlockingQueue<Runnable> deliveries = new LinkedBlockingQueue<>();
        installQueuedDispatcher(deliveries);
        Scheduler s = new Scheduler();
        CountDownLatch release = new CountDownLatch(1);
        try {
            s.setMaxConcurrency(1);
            CountDownLatch holding = new CountDownLatch(1);
            submit(s, "hold", "h0", "text", (text, precedingContext) -> {
                holding.countDown();
                release.await(5, TimeUnit.SECONDS);
                return "t";
            }, ALWAYS, new Collector());
            await(holding);
            AtomicBoolean enabled = new AtomicBoolean(true);
            CountingEngine engine = new CountingEngine("must not run");
            Collector obsolete = new Collector();
            submit(s, "disabled", "h1", "text", engine, taskId -> enabled.get(), obsolete);
            assertTrue(s.stats(), s.stats().contains("active=1, pending=1"));
            enabled.set(false);
            release.countDown();
            // Both workers have posted, so execution acceptance observed disabled before reenabling.
            Runnable first = take(deliveries);
            Runnable second = take(deliveries);
            assertEquals(0, engine.calls.get());
            enabled.set(true);
            first.run();
            second.run();
            obsolete.await();
            assertFalse(obsolete.accepted);
            assertNull(obsolete.translated);
            assertTrue(obsolete.error instanceof TranslationException);
            assertEquals(TranslationException.Kind.CANCELLED,
                    ((TranslationException) obsolete.error).kind());
            assertEquals(1, s.discardedCount());
            assertEquals(1, s.completedCount());
            assertEquals(0, s.failedCount());
            assertTrue(s.stats(), s.stats().contains("inFlight=0, active=0, pending=0"));
        } finally {
            release.countDown();
            s.shutdown();
        }
    }

    @Test
    public void errorIsClassifiedAndReported() throws Exception {
        Scheduler s = new Scheduler();
        try {
            Scheduler.Engine failing = (text, precedingContext) -> {
                throw new TranslationException(TranslationException.Kind.NETWORK, "boom");
            };
            Collector c = new Collector();
            submit(s, "1", "h1", "hello", failing, ALWAYS, c);
            c.await();
            assertTrue(c.accepted);
            assertNull(c.translated);
            assertTrue(c.error instanceof TranslationException);
            assertEquals(TranslationException.Kind.NETWORK, ((TranslationException) c.error).kind());
            assertEquals(1, s.failedCount());
        } finally {
            s.shutdown();
        }
    }

    @Test
    public void transientErrorIsAttemptedOnlyOnce() throws Exception {
        Scheduler s = new Scheduler();
        try {
            AtomicInteger calls = new AtomicInteger();
            Scheduler.Engine alwaysTimeout = (text, precedingContext) -> {
                calls.incrementAndGet();
                throw new TranslationException(TranslationException.Kind.TIMEOUT, "timeout");
            };
            Collector c = new Collector();
            submit(s, "1", "h1", "hello", alwaysTimeout, ALWAYS, c);
            c.await();
            assertEquals(1, calls.get());
            assertTrue(c.error instanceof TranslationException);
            assertEquals(1, s.failedCount());
        } finally {
            s.shutdown();
        }
    }

    @Test
    public void nonRetryableErrorIsNotRetried() throws Exception {
        Scheduler s = new Scheduler();
        try {
            AtomicInteger calls = new AtomicInteger();
            Scheduler.Engine authFail = (text, precedingContext) -> {
                calls.incrementAndGet();
                throw new TranslationException(TranslationException.Kind.HTTP, "unauthorized", 401, null);
            };
            Collector c = new Collector();
            submit(s, "1", "h1", "hello", authFail, ALWAYS, c);
            c.await();
            assertEquals(1, calls.get());
        } finally {
            s.shutdown();
        }
    }

    @Test
    public void transientFailureDoesNotRetryToSuccess() throws Exception {
        Scheduler s = new Scheduler();
        try {
            AtomicInteger calls = new AtomicInteger();
            Scheduler.Engine flaky = (text, precedingContext) -> {
                calls.incrementAndGet();
                throw new TranslationException(TranslationException.Kind.TIMEOUT, "timeout");
            };
            Collector c = new Collector();
            submit(s, "1", "h1", "hello", flaky, ALWAYS, c);
            c.await();
            assertEquals(1, calls.get());
            assertNull(c.translated);
            assertTrue(c.error instanceof TranslationException);
        } finally {
            s.shutdown();
        }
    }

    @Test
    public void rateLimitErrorHasReadableMessage() {
        TranslationException e = new TranslationException(TranslationException.Kind.RATE_LIMIT, "限流", 429, null);
        assertTrue(e.userMessage().contains("限流"));
    }

    @Test
    public void contentHashIsStableAndConfigSensitive() {
        String a = Scheduler.contentHash("hello", "cfg1");
        String b = Scheduler.contentHash("hello", "cfg1");
        String c = Scheduler.contentHash("hello", "cfg2");
        String d = Scheduler.contentHash("hello!", "cfg1");
        assertEquals(a, b);
        assertNotEquals(a, c);
        assertNotEquals(a, d);
        assertEquals(64, a.length());
    }

    @Test
    public void concurrencyLimitIsRespected() throws Exception {
        Scheduler s = new Scheduler();
        CountDownLatch release = new CountDownLatch(1);
        try {
            s.setMaxConcurrency(2);
            AtomicInteger concurrent = new AtomicInteger();
            AtomicInteger peak = new AtomicInteger();
            CountDownLatch entered = new CountDownLatch(2);
            CountDownLatch delivered = new CountDownLatch(6);
            Scheduler.Engine slow = (text, precedingContext) -> {
                int now = concurrent.incrementAndGet();
                peak.accumulateAndGet(now, Math::max);
                entered.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                    return "t";
                } finally {
                    concurrent.decrementAndGet();
                }
            };
            for (int i = 0; i < 6; i++) {
                submit(s, "id" + i, "h" + i, "text", slow, ALWAYS,
                        (taskId, accepted, translated, error) -> delivered.countDown());
            }
            await(entered);
            assertTrue(s.stats(), s.stats().contains("active=2, pending=4"));
            release.countDown();
            await(delivered);
            assertEquals(2, peak.get());
            assertEquals(6, s.completedCount());
        } finally {
            release.countDown();
            s.shutdown();
        }
    }

    @Test
    public void ninePlusBurstWaitsAndCompletesWithoutRejection() throws Exception {
        Scheduler s = new Scheduler();
        CountDownLatch release = new CountDownLatch(1);
        try {
            s.setMaxConcurrency(8);
            CountDownLatch entered = new CountDownLatch(8);
            AtomicInteger calls = new AtomicInteger();
            Scheduler.Engine blocking = (text, precedingContext) -> {
                calls.incrementAndGet();
                entered.countDown();
                release.await(5, TimeUnit.SECONDS);
                return "t";
            };
            List<Collector> collectors = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                Collector c = new Collector();
                collectors.add(c);
                submit(s, "burst" + i, "h", "text", blocking, ALWAYS, c);
            }
            await(entered);
            assertEquals(8, calls.get());
            assertTrue(s.stats(), s.stats().contains("active=8, pending=12"));
            release.countDown();
            for (Collector c : collectors) {
                c.await();
                assertTrue(c.accepted);
                assertNull(c.error);
            }
            assertEquals(20, calls.get());
            assertEquals(20, s.completedCount());
            assertEquals(0, s.failedCount());
        } finally {
            release.countDown();
            s.shutdown();
        }
    }

    @Test
    public void concurrencyLimitCanBeLoweredThenRestoredForSixExistingTasks() throws Exception {
        Scheduler s = new Scheduler();
        CountDownLatch firstRelease = new CountDownLatch(1);
        CountDownLatch secondRelease = new CountDownLatch(1);
        try {
            s.setMaxConcurrency(4);
            CountDownLatch firstEntered = new CountDownLatch(4);
            CountDownLatch firstDelivered = new CountDownLatch(4);
            CountDownLatch secondEntered = new CountDownLatch(2);
            CountDownLatch allDelivered = new CountDownLatch(6);
            AtomicInteger calls = new AtomicInteger();
            AtomicInteger interrupted = new AtomicInteger();
            Scheduler.Engine engine = (text, precedingContext) -> {
                calls.incrementAndGet();
                try {
                    if (text.equals("first")) {
                        firstEntered.countDown();
                        firstRelease.await(5, TimeUnit.SECONDS);
                    } else {
                        secondEntered.countDown();
                        secondRelease.await(5, TimeUnit.SECONDS);
                    }
                    return "t";
                } catch (InterruptedException e) {
                    interrupted.incrementAndGet();
                    throw e;
                }
            };
            for (int i = 0; i < 6; i++) {
                boolean first = i < 4;
                submit(s, "adjust" + i, "h", first ? "first" : "second", engine, ALWAYS,
                        (taskId, accepted, translated, error) -> {
                            if (first) firstDelivered.countDown();
                            allDelivered.countDown();
                        });
            }
            await(firstEntered);
            s.setMaxConcurrency(1);
            assertTrue(s.stats(), s.stats().contains("active=4, pending=2, concurrency=1"));
            assertEquals(0, interrupted.get());
            firstRelease.countDown();
            await(firstDelivered);
            // All original workers have finished; only one of the two pending tasks may be dispatched.
            assertTrue(s.stats(), s.stats().contains("active=1, pending=1, concurrency=1"));
            s.setMaxConcurrency(2);
            await(secondEntered);
            assertTrue(s.stats(), s.stats().contains("active=2, pending=0, concurrency=2"));
            secondRelease.countDown();
            await(allDelivered);
            assertEquals(6, calls.get());
            assertEquals(0, interrupted.get());
            assertEquals(6, s.completedCount());
        } finally {
            firstRelease.countDown();
            secondRelease.countDown();
            s.shutdown();
        }
    }

    @Test
    public void configuredConcurrencyIsClamped() {
        Scheduler s = new Scheduler();
        try {
            s.setMaxConcurrency(0);
            assertTrue(s.stats(), s.stats().contains("concurrency=1"));
            s.setMaxConcurrency(99);
            assertTrue(s.stats(), s.stats().contains("concurrency=8"));
        } finally {
            s.shutdown();
        }
    }

    @Test
    public void pendingTasksRunInFifoOrderWithoutOccupyingWorkers() throws Exception {
        Scheduler s = new Scheduler();
        CountDownLatch release = new CountDownLatch(1);
        try {
            s.setMaxConcurrency(1);
            CountDownLatch holding = new CountDownLatch(1);
            CountDownLatch delivered = new CountDownLatch(6);
            List<String> order = Collections.synchronizedList(new ArrayList<>());
            Scheduler.Engine engine = (text, precedingContext) -> {
                order.add(text);
                if (text.equals("0")) {
                    holding.countDown();
                    release.await(5, TimeUnit.SECONDS);
                }
                return text;
            };
            for (int i = 0; i < 6; i++) {
                submit(s, "fifo" + i, "h", String.valueOf(i), engine, ALWAYS,
                        (taskId, accepted, translated, error) -> delivered.countDown());
            }
            await(holding);
            assertTrue(s.stats(), s.stats().contains("active=1, pending=5"));
            assertEquals(1, order.size());
            release.countDown();
            await(delivered);
            for (int i = 0; i < 6; i++) assertEquals(String.valueOf(i), order.get(i));
        } finally {
            release.countDown();
            s.shutdown();
        }
    }

    @Test
    public void shutdownAfterSubmitDoesNotCrash() {
        Scheduler s = new Scheduler();
        submit(s, "1", "h1", "hello", new CountingEngine("t"), ALWAYS, new Collector());
        s.shutdown();
        assertTrue(s.stats().contains("scheduler["));
    }

    @Test
    public void submitAfterShutdownDeliversNothing() throws Exception {
        Scheduler s = new Scheduler();
        s.shutdown();
        Collector c = new Collector();
        submit(s, "1", "h1", "hello", new CountingEngine("t"), ALWAYS, c);
        assertFalse(c.latch.await(200, TimeUnit.MILLISECONDS));
    }

    @Test
    public void queueFullReportsExplicitFailureAndCountsIt() throws Exception {
        BlockingQueue<Runnable> deliveries = new LinkedBlockingQueue<>();
        installQueuedDispatcher(deliveries);
        Scheduler s = new Scheduler();
        CountDownLatch release = new CountDownLatch(1);
        try {
            s.setMaxConcurrency(1);
            CountDownLatch holding = new CountDownLatch(1);
            AtomicInteger calls = new AtomicInteger();
            Scheduler.Engine blocking = (text, precedingContext) -> {
                calls.incrementAndGet();
                holding.countDown();
                release.await(10, TimeUnit.SECONDS);
                return "t";
            };
            submit(s, "running", "h", "text", blocking, ALWAYS, new Collector());
            await(holding);
            for (int i = 0; i < 512; i++) {
                submit(s, "pending" + i, "h", "text", blocking, ALWAYS, new Collector());
            }
            assertTrue(s.stats(), s.stats().contains("active=1, pending=512"));
            TranslationTaskId rejectedId = id("overflow", "h");
            Collector rejected = new Collector();
            s.submit(rejectedId, "text", blocking, ALWAYS, rejected);
            Runnable rejection = take(deliveries);
            // Admission failures also remain deduped until main-thread delivery.
            s.submit(id("overflow", "h"), "text", blocking, ALWAYS, new Collector());
            assertTrue(deliveries.isEmpty());
            assertEquals(1, calls.get());
            rejection.run();
            rejected.await();
            assertSame(rejectedId, rejected.taskId);
            assertTrue(rejected.accepted);
            assertNull(rejected.translated);
            assertTrue(rejected.error instanceof TranslationException);
            assertTrue(rejected.error.getMessage().contains("512"));
            assertEquals(1, s.failedCount());
            assertEquals(0, s.completedCount());
            assertEquals(1, calls.get());
            assertTrue(s.stats(), s.stats().contains("inFlight=513"));
        } finally {
            s.shutdown();
            release.countDown();
        }
    }

    @Test
    public void recreatedSameVersionReplacesObsoletePendingAttempt() throws Exception {
        BlockingQueue<Runnable> deliveries = new LinkedBlockingQueue<>();
        installQueuedDispatcher(deliveries);
        Scheduler s = new Scheduler();
        CountDownLatch release = new CountDownLatch(1);
        try {
            s.setMaxConcurrency(1);
            CountDownLatch holding = new CountDownLatch(1);
            submit(s, "hold", "h", "text", (text, precedingContext) -> {
                holding.countDown();
                release.await(5, TimeUnit.SECONDS);
                return "t";
            }, ALWAYS, new Collector());
            await(holding);
            TranslationTaskId old = id("row", "h");
            TranslationTaskId replacement = id("row", "h");
            AtomicReference<TranslationTaskId> owner = new AtomicReference<>(old);
            Scheduler.Acceptance owns = candidate -> candidate.equals(owner.get());
            CountingEngine engine = new CountingEngine("t");
            Collector oldResult = new Collector();
            Collector newResult = new Collector();
            s.submit(old, "text", engine, owns, oldResult);
            owner.set(replacement);
            s.submit(replacement, "text", engine, owns, newResult);
            assertTrue(s.stats(), s.stats().contains("active=1, pending=1"));
            take(deliveries).run();
            oldResult.await();
            assertFalse(oldResult.accepted);
            assertSame(old, oldResult.taskId);
            // The rejected old callback must not erase the replacement's marker.
            assertTrue(s.stats(), s.stats().contains("inFlight=2"));
            Collector duplicate = new Collector();
            s.submit(id("row", "h"), "text", engine, owns, duplicate);
            assertTrue(deliveries.isEmpty());
            release.countDown();
            take(deliveries).run();
            take(deliveries).run();
            newResult.await();
            assertTrue(newResult.accepted);
            assertSame(replacement, newResult.taskId);
            assertEquals(1, engine.calls.get());
            assertEquals(1L, duplicate.latch.getCount());
            assertEquals(1, s.discardedCount());
        } finally {
            release.countDown();
            s.shutdown();
        }
    }

    @Test
    public void recreatedSameVersionWhileRunningKeepsReplacementDedupMarker() throws Exception {
        BlockingQueue<Runnable> deliveries = new LinkedBlockingQueue<>();
        installQueuedDispatcher(deliveries);
        Scheduler s = new Scheduler();
        CountDownLatch oldRelease = new CountDownLatch(1);
        CountDownLatch newRelease = new CountDownLatch(1);
        try {
            s.setMaxConcurrency(2);
            TranslationTaskId old = id("row", "h");
            TranslationTaskId replacement = id("row", "h");
            AtomicReference<TranslationTaskId> owner = new AtomicReference<>(old);
            Scheduler.Acceptance owns = candidate -> candidate.equals(owner.get());
            CountDownLatch oldEntered = new CountDownLatch(1);
            CountDownLatch newEntered = new CountDownLatch(1);
            AtomicInteger calls = new AtomicInteger();
            Scheduler.Engine engine = (text, precedingContext) -> {
                calls.incrementAndGet();
                if (text.equals("old")) {
                    oldEntered.countDown();
                    oldRelease.await(5, TimeUnit.SECONDS);
                } else {
                    newEntered.countDown();
                    newRelease.await(5, TimeUnit.SECONDS);
                }
                return "t";
            };
            Collector oldResult = new Collector();
            Collector newResult = new Collector();
            s.submit(old, "old", engine, owns, oldResult);
            await(oldEntered);
            owner.set(replacement);
            s.submit(replacement, "new", engine, owns, newResult);
            await(newEntered);
            assertTrue(s.stats(), s.stats().contains("inFlight=1, active=2"));
            oldRelease.countDown();
            take(deliveries).run();
            oldResult.await();
            assertFalse(oldResult.accepted);
            assertTrue(s.stats(), s.stats().contains("inFlight=1, active=1"));
            Collector duplicate = new Collector();
            s.submit(id("row", "h"), "new", engine, owns, duplicate);
            assertTrue(deliveries.isEmpty());
            newRelease.countDown();
            take(deliveries).run();
            newResult.await();
            assertTrue(newResult.accepted);
            assertSame(replacement, newResult.taskId);
            assertEquals(2, calls.get());
            assertEquals(1L, duplicate.latch.getCount());
            assertEquals(1, s.discardedCount());
            assertEquals(1, s.completedCount());
        } finally {
            oldRelease.countDown();
            newRelease.countDown();
            s.shutdown();
        }
    }

    @Test
    public void recreatedSameVersionWhileAwaitingUiDoesNotGetSilentlyDeduped() throws Exception {
        BlockingQueue<Runnable> deliveries = new LinkedBlockingQueue<>();
        installQueuedDispatcher(deliveries);
        Scheduler s = new Scheduler();
        try {
            TranslationTaskId old = id("row", "h");
            TranslationTaskId replacement = id("row", "h");
            AtomicReference<TranslationTaskId> owner = new AtomicReference<>(old);
            Scheduler.Acceptance owns = candidate -> candidate.equals(owner.get());
            CountingEngine engine = new CountingEngine("t");
            Collector oldResult = new Collector();
            Collector newResult = new Collector();
            s.submit(old, "text", engine, owns, oldResult);
            Runnable oldDelivery = take(deliveries);
            owner.set(replacement);
            s.submit(replacement, "text", engine, owns, newResult);
            Runnable newDelivery = take(deliveries);
            oldDelivery.run();
            assertFalse(oldResult.accepted);
            assertTrue(s.stats(), s.stats().contains("inFlight=1"));
            newDelivery.run();
            assertTrue(newResult.accepted);
            assertSame(replacement, newResult.taskId);
            assertEquals(2, engine.calls.get());
            assertTrue(s.stats(), s.stats().contains("inFlight=0"));
        } finally {
            s.shutdown();
        }
    }

    @Test
    public void fullQueuePrunesObsoleteLruRowsBeforeRejectingNewest() throws Exception {
        BlockingQueue<Runnable> deliveries = new LinkedBlockingQueue<>();
        installQueuedDispatcher(deliveries);
        Scheduler s = new Scheduler();
        CountDownLatch release = new CountDownLatch(1);
        try {
            s.setMaxConcurrency(1);
            CountDownLatch holding = new CountDownLatch(1);
            submit(s, "hold", "h", "text", (text, precedingContext) -> {
                holding.countDown();
                release.await(10, TimeUnit.SECONDS);
                return "t";
            }, ALWAYS, new Collector());
            await(holding);
            AtomicBoolean oldestValid = new AtomicBoolean(true);
            CountingEngine engine = new CountingEngine("t");
            Collector obsolete = new Collector();
            submit(s, "evicted", "h", "text", engine, taskId -> oldestValid.get(), obsolete);
            for (int i = 0; i < 511; i++) {
                submit(s, "pending" + i, "h", "text", engine, ALWAYS, new Collector());
            }
            assertTrue(s.stats(), s.stats().contains("active=1, pending=512"));
            oldestValid.set(false);
            Collector newest = new Collector();
            submit(s, "newest", "h", "text", engine, ALWAYS, newest);
            // Only the evicted task is delivered now, as discarded; newest remains in the bounded FIFO.
            take(deliveries).run();
            obsolete.await();
            assertFalse(obsolete.accepted);
            assertEquals(1L, newest.latch.getCount());
            assertTrue(deliveries.isEmpty());
            assertTrue(s.stats(), s.stats().contains("active=1, pending=512"));
            assertEquals(0, s.failedCount());
            assertEquals(1, s.discardedCount());
            assertEquals(0, engine.calls.get());
            release.countDown();
            // 1 holder + 511 remaining rows + newest all finish; none are rejected.
            for (int i = 0; i < 513; i++) take(deliveries).run();
            newest.await();
            assertTrue(newest.accepted);
            assertNull(newest.error);
            assertEquals(512, engine.calls.get());
            assertEquals(513, s.completedCount());
            assertEquals(0, s.failedCount());
        } finally {
            release.countDown();
            s.shutdown();
        }
    }

    @Test
    public void shutdownCancelsObsoleteRunningAttemptNoLongerInVersionMap() throws Exception {
        Scheduler s = new Scheduler();
        CountDownLatch release = new CountDownLatch(1);
        try {
            s.setMaxConcurrency(1);
            TranslationTaskId old = id("row", "h");
            TranslationTaskId replacement = id("row", "h");
            AtomicReference<TranslationTaskId> owner = new AtomicReference<>(old);
            CountDownLatch holding = new CountDownLatch(1);
            AtomicInteger cancels = new AtomicInteger();
            Scheduler.Engine engine = new Scheduler.Engine() {
                @Override
                public String translate(String text, String precedingContext) throws Exception {
                    holding.countDown();
                    release.await(5, TimeUnit.SECONDS);
                    return "t";
                }

                @Override
                public void cancel(Thread worker) {
                    cancels.incrementAndGet();
                    worker.interrupt();
                }
            };
            s.submit(old, "text", engine, candidate -> candidate.equals(owner.get()), new Collector());
            await(holding);
            owner.set(replacement);
            s.submit(replacement, "text", engine, candidate -> candidate.equals(owner.get()), new Collector());
            assertTrue(s.stats(), s.stats().contains("inFlight=1, active=1, pending=1"));
            assertTrue(s.shutdownAndAwait(5000));
            assertEquals(1, cancels.get());
        } finally {
            release.countDown();
            s.shutdown();
        }
    }

    @Test
    public void shutdownSuppressesRunningQueuedAndUndeliveredCallbacks() throws Exception {
        BlockingQueue<Runnable> deliveries = new LinkedBlockingQueue<>();
        installQueuedDispatcher(deliveries);
        Scheduler s = new Scheduler();
        CountDownLatch release = new CountDownLatch(1);
        try {
            s.setMaxConcurrency(1);
            AtomicInteger callbacks = new AtomicInteger();
            Scheduler.Callback callback = (taskId, accepted, translated, error) -> callbacks.incrementAndGet();
            submit(s, "finished", "h", "text", new CountingEngine("t"), ALWAYS, callback);
            Runnable finished = take(deliveries);
            CountDownLatch holding = new CountDownLatch(1);
            AtomicInteger calls = new AtomicInteger();
            AtomicInteger cancels = new AtomicInteger();
            Scheduler.Engine engine = new Scheduler.Engine() {
                @Override
                public String translate(String text, String precedingContext) throws Exception {
                    calls.incrementAndGet();
                    holding.countDown();
                    release.await(5, TimeUnit.SECONDS);
                    return "t";
                }

                @Override
                public void cancel(Thread worker) {
                    cancels.incrementAndGet();
                    worker.interrupt();
                }
            };
            submit(s, "running", "h", "text", engine, ALWAYS, callback);
            await(holding);
            for (int i = 0; i < 10; i++) {
                submit(s, "waiting" + i, "h", "text", engine, ALWAYS, callback);
            }
            assertTrue(s.stats(), s.stats().contains("active=1, pending=10"));
            assertTrue(s.shutdownAndAwait(5000));
            finished.run();
            for (Runnable delivery : deliveries) delivery.run();
            assertEquals(0, callbacks.get());
            assertEquals(1, calls.get());
            assertEquals(1, cancels.get());
            assertEquals(0, s.completedCount());
            assertTrue(s.stats(), s.stats().contains("inFlight=0, active=0, pending=0"));
        } finally {
            release.countDown();
            s.shutdown();
        }
    }

    @Test
    public void callbackCanShutdownAndAwaitWhileAnotherWorkerReleasesItsSlot() throws Exception {
        BlockingQueue<Runnable> deliveries = new LinkedBlockingQueue<>();
        installQueuedDispatcher(deliveries);
        Scheduler s = new Scheduler();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch engineExited = new CountDownLatch(1);
        try {
            s.setMaxConcurrency(2);
            AtomicBoolean terminatedInCallback = new AtomicBoolean();
            AtomicInteger callbacks = new AtomicInteger();
            Collector completed = new Collector();
            submit(s, "finished", "h", "text", new CountingEngine("t"), ALWAYS,
                    (taskId, accepted, translated, error) -> {
                        callbacks.incrementAndGet();
                        // Shutdown interrupts the other engine; its finally must acquire lifecycleLock.
                        terminatedInCallback.set(s.shutdownAndAwait(5000));
                        completed.onResult(taskId, accepted, translated, error);
                    });
            Runnable delivery = take(deliveries);
            Scheduler.Engine blocking = (text, precedingContext) -> {
                holding.countDown();
                try {
                    release.await();
                    return "t";
                } finally {
                    engineExited.countDown();
                }
            };
            submit(s, "active", "h", "text", blocking, ALWAYS,
                    (taskId, accepted, translated, error) -> callbacks.incrementAndGet());
            await(holding);
            assertTrue(s.stats(), s.stats().contains("active=1"));
            delivery.run();
            completed.await();
            await(engineExited);
            assertTrue("callback must await termination without holding the worker's lock",
                    terminatedInCallback.get());
            assertTrue(completed.accepted);
            assertEquals("t", completed.translated);
            assertNull(completed.error);
            // The reserved callback finishes, but no delivery can reserve after shutdown.
            delivery.run();
            for (Runnable suppressed : deliveries) suppressed.run();
            assertEquals(1, callbacks.get());
            assertEquals(1, s.completedCount());
            assertEquals(0, s.failedCount());
            assertEquals(0, s.discardedCount());
            assertTrue(s.stats(), s.stats().contains("inFlight=0, active=0, pending=0"));
        } finally {
            release.countDown();
            assertTrue("cleanup must terminate workers", s.shutdownAndAwait(5000));
        }
    }

    @Test
    public void mainThreadEpochSuppressesRetiredDispatcherCallbacks() throws Exception {
        BlockingQueue<Runnable> deliveries = new LinkedBlockingQueue<>();
        installQueuedDispatcher(deliveries);
        Scheduler s = new Scheduler();
        try {
            Collector c = new Collector();
            submit(s, "1", "h1", "text", new CountingEngine("t"), ALWAYS, c);
            Runnable old = take(deliveries);
            Scheduler.MainThread.clear();
            Scheduler.MainThread.setDirectDispatcherForTests();
            old.run();
            assertEquals(1L, c.latch.getCount());
            assertEquals(0, s.completedCount());
            assertTrue(s.stats(), s.stats().contains("inFlight=1"));
        } finally {
            s.shutdown();
        }
    }

    @Test
    public void sameMessageWithNewContentIsNotDeduped() throws Exception {
        Scheduler s = new Scheduler();
        CountDownLatch release = new CountDownLatch(1);
        try {
            CountDownLatch entered = new CountDownLatch(2);
            AtomicInteger calls = new AtomicInteger();
            Scheduler.Engine blocking = (text, precedingContext) -> {
                calls.incrementAndGet();
                entered.countDown();
                release.await(5, TimeUnit.SECONDS);
                return "t";
            };
            submit(s, "1", "old-hash", "old text", blocking, ALWAYS, new Collector());
            Collector edited = new Collector();
            submit(s, "1", "new-hash", "new text", blocking, ALWAYS, edited);
            await(entered);
            assertEquals(2, calls.get());
            release.countDown();
            edited.await();
        } finally {
            release.countDown();
            s.shutdown();
        }
    }

    @Test
    public void sameMessageAndHashWithNewGenerationIsNotDeduped() throws Exception {
        Scheduler s = new Scheduler();
        CountDownLatch release = new CountDownLatch(1);
        try {
            CountDownLatch entered = new CountDownLatch(2);
            Scheduler.Engine blocking = (text, precedingContext) -> {
                entered.countDown();
                release.await(5, TimeUnit.SECONDS);
                return "t";
            };
            Collector old = new Collector();
            Collector next = new Collector();
            s.submit(TranslationTaskId.create("1", "h", 7), "text", blocking, ALWAYS, old);
            s.submit(TranslationTaskId.create("1", "h", 8), "text", blocking, ALWAYS, next);
            await(entered);
            release.countDown();
            old.await();
            next.await();
        } finally {
            release.countDown();
            s.shutdown();
        }
    }

    @Test
    public void staleWhilePendingDoesNotCallEngine() throws Exception {
        Scheduler s = new Scheduler();
        CountDownLatch release = new CountDownLatch(1);
        try {
            s.setMaxConcurrency(1);
            CountDownLatch holding = new CountDownLatch(1);
            AtomicInteger calls = new AtomicInteger();
            Scheduler.Engine engine = (text, precedingContext) -> {
                calls.incrementAndGet();
                holding.countDown();
                release.await(5, TimeUnit.SECONDS);
                return "t";
            };
            submit(s, "hold", "h0", "text", engine, ALWAYS, new Collector());
            await(holding);
            AtomicBoolean valid = new AtomicBoolean(true);
            Collector stale = new Collector();
            submit(s, "1", "h1", "text", engine, taskId -> valid.get(), stale);
            valid.set(false);
            release.countDown();
            stale.await();
            assertEquals(1, calls.get());
            assertFalse(stale.accepted);
            assertEquals(1, s.discardedCount());
        } finally {
            release.countDown();
            s.shutdown();
        }
    }

    @Test
    public void shutdownAndAwaitWaitsForTermination() throws Exception {
        Scheduler s = new Scheduler();
        CountDownLatch holding = new CountDownLatch(1);
        try {
            Scheduler.Engine slow = (text, precedingContext) -> {
                holding.countDown();
                Thread.sleep(5000);
                return "t";
            };
            submit(s, "1", "h1", "text", slow, ALWAYS, new Collector());
            await(holding);
            assertTrue(s.shutdownAndAwait(5000));
        } finally {
            s.shutdown();
        }
    }

    @Test
    public void resetTimeoutRetainsRetiredSchedulerEvenAcrossResetAndNewInstance() throws Exception {
        assertTrue(Scheduler.resetAndAwait(5000));
        Scheduler retired = Scheduler.get();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch holding = new CountDownLatch(1);
        try {
            Scheduler.Engine uncooperative = (text, precedingContext) -> {
                holding.countDown();
                boolean interrupted = false;
                while (release.getCount() != 0) {
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
                if (interrupted) Thread.currentThread().interrupt();
                return "t";
            };
            submit(retired, "1", "h1", "text", uncooperative, ALWAYS, new Collector());
            await(holding);
            assertFalse("live worker must cause timeout", Scheduler.resetAndAwait(0));
            assertFalse("second check must still see the retired worker", Scheduler.resetAndAwait(0));
            Scheduler replacement = Scheduler.get();
            assertNotSame(retired, replacement);
            Scheduler.reset();
            assertFalse("reset must not erase the timed-out retired instance", Scheduler.resetAndAwait(0));
            release.countDown();
            assertTrue(Scheduler.resetAndAwait(5000));
            assertTrue(Scheduler.resetAndAwait(0));
        } finally {
            release.countDown();
            assertTrue("cleanup must terminate retired workers", Scheduler.resetAndAwait(5000));
        }
    }

    @Test
    public void acceptanceAndCallbackSeeCompleteRecordedIdentity() throws Exception {
        Scheduler s = new Scheduler();
        try {
            TranslationTaskId taskId = TranslationTaskId.create("1", "abc123", 123);
            AtomicReference<TranslationTaskId> seen = new AtomicReference<>();
            AtomicInteger validations = new AtomicInteger();
            Scheduler.Acceptance spy = candidate -> {
                seen.set(candidate);
                validations.incrementAndGet();
                return taskId.equals(candidate);
            };
            Collector c = new Collector();
            s.submit(taskId, "hello", new CountingEngine("t"), spy, c);
            c.await();
            assertSame(taskId, seen.get());
            assertSame(taskId, c.taskId);
            assertEquals(2, validations.get());
            assertEquals(123, seen.get().generation);
            assertEquals("abc123", seen.get().contentHash);
        } finally {
            s.shutdown();
        }
    }
}
