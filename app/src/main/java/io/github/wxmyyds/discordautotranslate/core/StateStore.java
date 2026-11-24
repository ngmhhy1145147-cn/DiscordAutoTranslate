package io.github.wxmyyds.discordautotranslate.core;

import androidx.annotation.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded translation records: content, source snapshot and active task share one lifetime. */
public final class StateStore {
    public enum Status { IDLE, TRANSLATING, TRANSLATED, FAILED }

    public static final class Entry {
        public final String messageId;
        public volatile String contentHash;
        public volatile String translatedText;
        public volatile Status status = Status.IDLE;
        public volatile boolean showingOriginal;
        public volatile String lastError;
        private TranslationTaskId task;
        private Source source;

        Entry(String messageId, String contentHash) {
            this.messageId = messageId;
            this.contentHash = contentHash;
        }
    }

    /** Opaque producer snapshot; the repository does not interpret host content. */
    public static final class Source {
        public final Object content;
        public final boolean revisionKnown;
        @Nullable public final String editedTimestamp;

        public Source(Object content, boolean revisionKnown, @Nullable String editedTimestamp) {
            this.content = content;
            this.revisionKnown = revisionKnown;
            this.editedTimestamp = editedTimestamp;
        }
    }

    private static final StateStore INSTANCE = new StateStore();
    private static final int MAX_ENTRIES = 512;
    // Rendering/producer access moves records explicitly; ownership checks must not alter LRU.
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private int generation = 1;

    private StateStore() {}
    public static StateStore get() { return INSTANCE; }
    public synchronized int generation() { return generation; }

    public synchronized void onNewGeneration() {
        generation++;
        entries.clear();
    }

    /** LRU includes every status; evicted work loses ownership and cannot resurrect a record. */
    public synchronized Entry obtain(String messageId, String contentHash) {
        Entry entry = entries.get(messageId);
        if (entry == null || !contentHash.equals(entry.contentHash)) {
            entry = new Entry(messageId, contentHash);
            entries.remove(messageId);
            entries.put(messageId, entry);
            if (entries.size() > MAX_ENTRIES) {
                entries.remove(entries.keySet().iterator().next());
            }
        } else {
            entries.remove(messageId);
            entries.put(messageId, entry);
        }
        return entry;
    }

    @Nullable public synchronized Entry peek(String messageId) {
        Entry entry = entries.remove(messageId);
        if (entry != null) entries.put(messageId, entry);
        return entry;
    }

    /** Attach only to a current record; source and state are evicted/reset together. */
    public synchronized void rememberSource(String messageId, Source source) {
        Entry entry = entries.get(messageId);
        if (entry != null) entry.source = source;
    }

    @Nullable public synchronized Source source(String messageId) {
        Entry entry = entries.get(messageId);
        return entry == null ? null : entry.source;
    }

    /** One attempt owns all subsequent transitions, including cancellation. */
    @Nullable public synchronized TranslationTaskId begin(Entry expected) {
        if (entries.get(expected.messageId) != expected || expected.status != Status.IDLE) return null;
        TranslationTaskId task = TranslationTaskId.create(expected.messageId, expected.contentHash, generation);
        expected.task = task;
        expected.lastError = null;
        expected.status = Status.TRANSLATING;
        return task;
    }

    public synchronized boolean owns(TranslationTaskId task) {
        Entry entry = entries.get(task.messageId);
        return task.generation == generation && entry != null
                && task.contentHash.equals(entry.contentHash) && task.equals(entry.task);
    }

    /** Reject stale attempts before touching the current record, even for rejected results. */
    public synchronized boolean complete(TranslationTaskId task, boolean accepted,
                                        @Nullable String translated, @Nullable String error) {
        if (!owns(task)) return false;
        Entry entry = entries.get(task.messageId);
        entry.task = null;
        if (!accepted) {
            entry.status = entry.translatedText == null ? Status.IDLE : Status.TRANSLATED;
        } else if (error != null || translated == null || translated.isEmpty()) {
            entry.status = Status.FAILED;
            entry.lastError = error == null ? "未知错误" : error;
        } else {
            entry.translatedText = translated;
            entry.lastError = null;
            entry.status = Status.TRANSLATED;
            entry.showingOriginal = false;
        }
        return true;
    }

    /** Synchronous cache hits use the same state owner as async results. */
    public synchronized boolean useCached(Entry entry, String translated) {
        if (entries.get(entry.messageId) != entry || entry.status != Status.IDLE) return false;
        entry.translatedText = translated;
        entry.status = Status.TRANSLATED;
        entry.showingOriginal = false;
        return true;
    }

    public synchronized boolean rejectRender(String id, String translated) {
        Entry entry = entries.get(id);
        if (entry == null || entry.status != Status.TRANSLATED
                || !translated.equals(entry.translatedText)) return false;
        entry.translatedText = null;
        entry.showingOriginal = true;
        entry.status = Status.FAILED;
        entry.lastError = "译文结构与原消息不匹配，已保留原文。";
        return true;
    }

    public synchronized boolean toggle(String id) {
        Entry entry = entries.get(id);
        if (entry == null || entry.status != Status.TRANSLATED || entry.translatedText == null) return false;
        entry.showingOriginal = !entry.showingOriginal;
        return true;
    }

    public synchronized boolean prepareRetry(Entry entry) {
        if (entries.get(entry.messageId) != entry || entry.status != Status.FAILED) return false;
        entry.status = Status.IDLE;
        entry.lastError = null;
        return true;
    }

    synchronized int size() { return entries.size(); }
}
