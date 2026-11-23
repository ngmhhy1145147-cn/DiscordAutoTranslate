package io.github.wxmyyds.discordautotranslate.core;

import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Configuration persistence/sync policy, independent of service and Activity lifecycles. */
public final class ConfigurationSync {
    public static final List<String> CONFIG_KEYS = Collections.unmodifiableList(Arrays.asList(
            Config.KEY_ENABLED, Config.KEY_LOGGING_ENABLED, Config.KEY_TRANSLATION_SCOPE, Config.KEY_API_URL,
            Config.KEY_API_KEY, Config.KEY_MODEL,
            Config.KEY_TARGET_LANG, Config.KEY_EXTRA_HEADERS, Config.KEY_SYSTEM_PROMPT,
            Config.KEY_PROTOCOL, Config.KEY_STREAMING, Config.KEY_TIMEOUT_MS,
            Config.KEY_MAX_CONCURRENCY, Config.KEY_TRANSLATE_MESSAGES,
            Config.KEY_TRANSLATE_SERVER_NAMES, Config.KEY_TRANSLATE_CHANNEL_NAMES,
            Config.KEY_CONTEXT_ENABLED, Config.KEY_CONTEXT_CHARACTERS, Config.KEY_GLOSSARY_LIBRARY));
    private static final String KEY_LOCAL_INITIALIZED = "settings_initialized";
    private static final String[] LOCAL_COMMIT_KEYS = localCommitKeys();

    public enum Direction { IMPORT, EXPORT, SAVE }
    public enum Status {
        SUCCESS, INVALID_SOURCE, LOCAL_COMMIT_FAILED, REMOTE_COMMIT_FAILED, POISONED, FAILED
    }

    public static final class Result {
        public final Direction direction;
        public final Status status;
        /** A successful local transaction, even when a subsequent remote write fails. */
        public final boolean localCommitted;
        public final PreferenceCommit.Result transaction;
        public final Throwable failure;

        private Result(Direction direction, Status status, boolean localCommitted,
                       PreferenceCommit.Result transaction, Throwable failure) {
            this.direction = direction;
            this.status = status;
            this.localCommitted = localCommitted;
            this.transaction = transaction;
            this.failure = failure;
        }

        public boolean succeeded() {
            return status == Status.SUCCESS;
        }

        public boolean imported() {
            return direction == Direction.IMPORT && succeeded() && localCommitted;
        }
    }

    /** Detached local state, captured under the transaction monitor on a persistence worker. */
    public static final class LocalState {
        public final Config.Snapshot snapshot;
        public final boolean poisoned;

        private LocalState(Config.Snapshot snapshot, boolean poisoned) {
            this.snapshot = snapshot;
            this.poisoned = poisoned;
        }
    }

    /** May wait for another Activity's commit; never call from the UI thread. */
    public static LocalState loadLocal(SharedPreferences local) {
        synchronized (PreferenceCommit.class) {
            if (PreferenceCommit.isPoisoned(local)) {
                return new LocalState(Config.invalidSnapshot(), true);
            }
            try {
                Values values = Values.validate(local.getAll());
                MemoryPrefs detached = new MemoryPrefs();
                for (Map.Entry<String, Object> entry : values.raw.entrySet()) {
                    detached.put(entry.getKey(), entry.getValue());
                }
                return new LocalState(Config.read(detached), false);
            } catch (RuntimeException invalid) {
                return new LocalState(Config.invalidSnapshot(), false);
            }
        }
    }

    private ConfigurationSync() {}

    private static String[] localCommitKeys() {
        String[] keys = new String[CONFIG_KEYS.size() + 1];
        for (int i = 0; i < CONFIG_KEYS.size(); i++) {
            keys[i] = CONFIG_KEYS.get(i);
        }
        keys[CONFIG_KEYS.size()] = KEY_LOCAL_INITIALIZED;
        return keys;
    }

    /**
     * The local store becomes authoritative only with confirmed initialized persistence.
     * A poisoned store cannot import or export, regardless of its current in-memory marker.
     * No retry or change of direction follows a failed operation.
     */
    public static Result synchronize(SharedPreferences local, SharedPreferences remote) {
        synchronized (PreferenceCommit.class) {
            Direction direction = null;
            try {
                if (PreferenceCommit.isPoisoned(local)) {
                    return result(null, Status.POISONED, false, null, null);
                }
                Map<String, ?> localValues = local.getAll();
                if (localValues.containsKey(KEY_LOCAL_INITIALIZED)
                        && !(localValues.get(KEY_LOCAL_INITIALIZED) instanceof Boolean)) {
                    return result(null, Status.INVALID_SOURCE, false, null, null);
                }
                boolean initialized = Boolean.TRUE.equals(localValues.get(KEY_LOCAL_INITIALIZED));
                direction = initialized ? Direction.EXPORT : Direction.IMPORT;
                Values values = Values.validate(initialized ? localValues : remote.getAll());
                if (initialized) {
                    return export(remote, values, direction, false, null);
                }
                PreferenceCommit.Result transaction = commitLocal(local, values);
                return result(direction, transaction.committed ? Status.SUCCESS
                        : Status.LOCAL_COMMIT_FAILED, transaction.committed,
                        transaction, transaction.failure);
            } catch (IllegalArgumentException invalid) {
                return result(direction, Status.INVALID_SOURCE, false, null, invalid);
            } catch (Throwable failure) {
                return result(direction, Status.FAILED, false, null, failure);
            }
        }
    }

    /** Explicit save/reset may recover poison; null is an explicitly confirmed reset. */
    public static Result save(SharedPreferences local, SharedPreferences remote,
                              Config.Snapshot snapshot) {
        synchronized (PreferenceCommit.class) {
            try {
                Values values = snapshot == null ? Values.validate(Collections.emptyMap())
                        : Values.fromSnapshot(snapshot);
                PreferenceCommit.Result transaction = commitLocal(local, values);
                if (!transaction.committed) {
                    return result(Direction.SAVE, Status.LOCAL_COMMIT_FAILED, false,
                            transaction, transaction.failure);
                }
                if (remote == null) {
                    return result(Direction.SAVE, Status.SUCCESS, true, transaction, null);
                }
                return export(remote, values, Direction.SAVE, true, transaction);
            } catch (IllegalArgumentException invalid) {
                return result(Direction.SAVE, Status.INVALID_SOURCE, false, null, invalid);
            } catch (Throwable failure) {
                return result(Direction.SAVE, Status.FAILED, false, null, failure);
            }
        }
    }

    private static PreferenceCommit.Result commitLocal(SharedPreferences local, Values values) {
        return PreferenceCommit.commit(local, local.edit(), LOCAL_COMMIT_KEYS, editor -> {
            values.stage(editor);
            editor.putBoolean(KEY_LOCAL_INITIALIZED, true);
        });
    }

    /** Remote storage is not a local transaction and never receives the authority marker. */
    private static Result export(SharedPreferences remote, Values values, Direction direction,
                                 boolean localCommitted, PreferenceCommit.Result transaction) {
        try {
            SharedPreferences.Editor editor = remote.edit();
            values.stage(editor);
            return result(direction, editor.commit() ? Status.SUCCESS
                    : Status.REMOTE_COMMIT_FAILED, localCommitted, transaction, null);
        } catch (Throwable failure) {
            return result(direction, Status.REMOTE_COMMIT_FAILED, localCommitted,
                    transaction, failure);
        }
    }

    private static Result result(Direction direction, Status status, boolean localCommitted,
                                 PreferenceCommit.Result transaction, Throwable failure) {
        return new Result(direction, status, localCommitted, transaction, failure);
    }

    /** Fully validated, detached raw configuration; no target editor exists during validation. */
    private static final class Values {
        final Map<String, Object> raw = new LinkedHashMap<>();

        static Values validate(Map<String, ?> source) {
            Values values = new Values();
            MemoryPrefs view = new MemoryPrefs();
            for (String key : CONFIG_KEYS) {
                if (!source.containsKey(key)) {
                    continue;
                }
                Object value = source.get(key);
                // Reject present null and unsupported types before MemoryPrefs can treat
                // them as defaults. Config.read then enforces each key's type and meaning.
                if (!(value instanceof String || value instanceof Boolean
                        || value instanceof Integer)) {
                    throw new IllegalArgumentException("Invalid configuration value type");
                }
                values.raw.put(key, value);
                view.put(key, value);
            }
            if (Config.read(view).configError != null) {
                throw new IllegalArgumentException("Invalid configuration snapshot");
            }
            return values;
        }

        static Values fromSnapshot(Config.Snapshot snapshot) {
            if (snapshot.configError != null) {
                throw new IllegalArgumentException("Invalid configuration snapshot");
            }
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put(Config.KEY_ENABLED, snapshot.enabled);
            raw.put(Config.KEY_LOGGING_ENABLED, snapshot.loggingEnabled);
            if (!TranslationScope.ALL.equals(snapshot.translationScope)) {
                raw.put(Config.KEY_TRANSLATION_SCOPE, snapshot.translationScope.toJson());
            }
            putUnlessDefault(raw, Config.KEY_TRANSLATE_MESSAGES,
                    snapshot.translateMessages, Config.DEF_TRANSLATE_MESSAGES);
            putUnlessDefault(raw, Config.KEY_TRANSLATE_SERVER_NAMES,
                    snapshot.translateServerNames, Config.DEF_TRANSLATE_SERVER_NAMES);
            putUnlessDefault(raw, Config.KEY_TRANSLATE_CHANNEL_NAMES,
                    snapshot.translateChannelNames, Config.DEF_TRANSLATE_CHANNEL_NAMES);
            putUnlessDefault(raw, Config.KEY_CONTEXT_ENABLED,
                    snapshot.contextEnabled, Config.DEF_CONTEXT_ENABLED);
            putUnlessDefault(raw, Config.KEY_CONTEXT_CHARACTERS,
                    snapshot.contextCharacters, Config.DEF_CONTEXT_CHARACTERS);
            if (!snapshot.glossaryLibrary.packs().isEmpty()) {
                raw.put(Config.KEY_GLOSSARY_LIBRARY, snapshot.glossaryLibrary.toJson());
            }
            raw.put(Config.KEY_PROTOCOL, snapshot.protocol);
            raw.put(Config.KEY_STREAMING, snapshot.streaming);
            putUnlessDefault(raw, Config.KEY_API_URL, snapshot.apiUrl, Config.DEF_API_URL);
            putUnlessDefault(raw, Config.KEY_API_KEY, snapshot.apiKey, Config.DEF_API_KEY);
            putUnlessDefault(raw, Config.KEY_MODEL, snapshot.model, Config.DEF_MODEL);
            putUnlessDefault(raw, Config.KEY_TARGET_LANG, snapshot.targetLang, Config.DEF_TARGET_LANG);
            putUnlessDefault(raw, Config.KEY_EXTRA_HEADERS, snapshot.extraHeaders,
                    Config.DEF_EXTRA_HEADERS);
            putUnlessDefault(raw, Config.KEY_SYSTEM_PROMPT, snapshot.systemPrompt,
                    Config.DEF_SYSTEM_PROMPT);
            putUnlessDefault(raw, Config.KEY_TIMEOUT_MS, snapshot.timeoutMs, Config.DEF_TIMEOUT_MS);
            putUnlessDefault(raw, Config.KEY_MAX_CONCURRENCY, snapshot.maxConcurrency,
                    Config.DEF_MAX_CONCURRENCY);
            return validate(raw);
        }

        private static void putUnlessDefault(Map<String, Object> values, String key,
                                             Object value, Object defaultValue) {
            if (!defaultValue.equals(value)) {
                values.put(key, value);
            }
        }

        void stage(SharedPreferences.Editor editor) {
            for (String key : CONFIG_KEYS) {
                if (!raw.containsKey(key)) {
                    editor.remove(key);
                    continue;
                }
                Object value = raw.get(key);
                if (value instanceof String) {
                    editor.putString(key, (String) value);
                } else if (value instanceof Boolean) {
                    editor.putBoolean(key, (Boolean) value);
                } else {
                    editor.putInt(key, (Integer) value);
                }
            }
        }
    }
}
