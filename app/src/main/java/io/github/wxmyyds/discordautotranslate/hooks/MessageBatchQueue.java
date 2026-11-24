package io.github.wxmyyds.discordautotranslate.hooks;

import androidx.annotation.Nullable;

import io.github.wxmyyds.discordautotranslate.core.Scheduler;
import io.github.wxmyyds.discordautotranslate.core.TranslationException;
import io.github.wxmyyds.discordautotranslate.core.TranslationTaskId;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Groups unsubmitted message owners by channel and closes each group exactly once. */
final class MessageBatchQueue {
    static final long MAX_WAIT_MS = 120L;
    private static final int MAX_CHANNELS = 32;
    private static final int MAX_ITEMS = 128;

    interface Timer {
        boolean postDelayed(Runnable callback, long delayMs);
    }

    interface Dispatch {
        void submit(MessageLocation location, List<Scheduler.BatchItem> messages,
                    @Nullable Throwable error);
    }

    private final Map<MessageLocation, Window> pending = new LinkedHashMap<>();
    private final Timer timer;
    private final Dispatch dispatch;
    private long nextWindow;

    MessageBatchQueue(Timer timer, Dispatch dispatch) {
        this.timer = timer;
        this.dispatch = dispatch;
    }

    void offer(MessageLocation location, Scheduler.BatchItem message, int characters, int budget,
               Scheduler.Acceptance acceptance) {
        Window opened = null;
        List<Window> ready = new ArrayList<>(2);
        synchronized (this) {
            Window window = pending.get(location);
            if (window != null) {
                window.prune(acceptance);
                if (window.items.containsKey(message.id)) return;
                if (window.items.isEmpty()) {
                    pending.remove(location);
                    window = null;
                } else if (window.characters + characters > budget) {
                    ready.add(pending.remove(location));
                    window = null;
                }
            }
            if (window == null) {
                window = new Window(location, ++nextWindow);
                if (pending.size() == MAX_CHANNELS) {
                    Iterator<Window> oldest = pending.values().iterator();
                    ready.add(oldest.next());
                    oldest.remove();
                }
                pending.put(location, window);
                opened = window;
            }
            window.items.put(message.id, new CountedMessage(message, characters));
            window.characters += characters;
            if (window.characters >= budget || window.items.size() == MAX_ITEMS) {
                ready.add(pending.remove(location));
                opened = null;
            }
        }
        for (Window window : ready) send(window, null);
        if (opened != null) {
            long ticket = opened.ticket;
            if (!timer.postDelayed(() -> expire(location, ticket, null), MAX_WAIT_MS)) {
                expire(location, ticket, new TranslationException(TranslationException.Kind.UNKNOWN,
                        "无法安排精翻批次"));
            }
        }
    }

    private void expire(MessageLocation location, long ticket, @Nullable Throwable error) {
        Window window;
        synchronized (this) {
            window = pending.get(location);
            if (window == null || window.ticket != ticket) return;
            pending.remove(location);
        }
        send(window, error);
    }

    private void send(Window window, @Nullable Throwable error) {
        List<Scheduler.BatchItem> messages = new ArrayList<>(window.items.size());
        for (CountedMessage item : window.items.values()) messages.add(item.message);
        messages.sort((left, right) -> Long.compareUnsigned(
                Long.parseUnsignedLong(left.id.messageId), Long.parseUnsignedLong(right.id.messageId)));
        dispatch.submit(window.location, messages, error);
    }

    synchronized List<Scheduler.BatchItem> clear() {
        List<Scheduler.BatchItem> discarded = new ArrayList<>();
        for (Window window : pending.values()) {
            for (CountedMessage item : window.items.values()) discarded.add(item.message);
        }
        pending.clear();
        return discarded;
    }

    private static final class CountedMessage {
        final Scheduler.BatchItem message;
        final int characters;

        CountedMessage(Scheduler.BatchItem message, int characters) {
            this.message = message;
            this.characters = characters;
        }
    }

    private static final class Window {
        final MessageLocation location;
        final long ticket;
        final Map<TranslationTaskId, CountedMessage> items = new LinkedHashMap<>();
        int characters;

        Window(MessageLocation location, long ticket) {
            this.location = location;
            this.ticket = ticket;
        }

        void prune(Scheduler.Acceptance acceptance) {
            Iterator<CountedMessage> it = items.values().iterator();
            while (it.hasNext()) {
                CountedMessage item = it.next();
                if (!acceptance.isStillValid(item.message.id)) {
                    characters -= item.characters;
                    it.remove();
                }
            }
        }
    }
}
