package io.github.wxmyyds.discordautotranslate.hooks;

import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.wxmyyds.discordautotranslate.core.Config;
import io.github.wxmyyds.discordautotranslate.core.EnglishDetector;
import io.github.wxmyyds.discordautotranslate.core.HookConfiguration;
import io.github.wxmyyds.discordautotranslate.core.ProjectLog;
import io.github.wxmyyds.discordautotranslate.core.Reflect;
import io.github.wxmyyds.discordautotranslate.core.Scheduler;
import io.github.wxmyyds.discordautotranslate.core.StateStore;
import io.github.wxmyyds.discordautotranslate.core.TranslationCache;
import io.github.wxmyyds.discordautotranslate.core.TranslationScope;
import io.github.wxmyyds.discordautotranslate.core.TranslationTaskId;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Name translation projects onto specific native label views after their original binder runs. */
public final class NameHooks {
    static final int MAX_NAME_CHARACTERS = 100;
    private static final Map<TextView, Binding> BINDINGS = new WeakHashMap<>();
    private static volatile boolean hasBindings;

    enum Kind { SERVER, CHANNEL }

    private static final class Binding {
        final String key;
        final String hash;
        final CharSequence original;
        @Nullable String rendered;

        Binding(String key, String hash, CharSequence original) {
            this.key = key;
            this.hash = hash;
            this.original = original;
        }

        boolean accepts(TranslationTaskId task) {
            return key.equals(task.messageId) && hash.equals(task.contentHash);
        }
    }

    private NameHooks() {}

    public static void install(XposedModule module, ClassLoader loader, HookConfiguration config,
            Set<String> installedHookIds, List<XposedInterface.HookHandle> handles) {
        for (String id : config.nameHooks()) {
            JSONObject spec = config.hook(id);
            Method method = config.findMethod(id, loader);
            if (spec == null || method == null) {
                throw new IllegalStateException("Configured name binding target unavailable");
            }
            int modelArgument = spec.optInt("modelArgument");
            int viewArgument = spec.optInt("viewArgument");
            handles.add(module.hook(method).setId("dat." + id)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        try (HookRuntime.Lease lease = HookRuntime.enter()) {
                            Object result = chain.proceed();
                            if (lease != null && Looper.myLooper() == Looper.getMainLooper()) {
                                Object viewSource = viewArgument == -1
                                        ? chain.getThisObject() : chain.getArg(viewArgument);
                                bind(spec, viewSource, chain.getArg(modelArgument));
                            }
                            return result;
                        }
                    }));
            installedHookIds.add(id);
            ProjectLog.infoOnce(id + ".target", id + " target=" + method.toGenericString());
        }
    }

    private static void bind(JSONObject spec, Object viewSource, Object modelSource) {
        Object holder = readPath(viewSource, spec.optJSONArray("viewPath"));
        if (!(holder instanceof View)) return;
        View view = (View) holder;
        Object model = readPath(modelSource, spec.optJSONArray("modelPath"));
        String guild = snowflake(readPath(model, spec.optJSONArray("guildIdPath")));
        String channel = snowflake(readPath(model, spec.optJSONArray("channelIdPath")));
        HookBridge.TranslationConfiguration config = HookBridge.get().translationConfiguration();
        bindLabel(view.findViewById(spec.optInt("serverViewId")), config, Kind.SERVER, guild, channel);
        bindLabel(view.findViewById(spec.optInt("channelViewId")), config, Kind.CHANNEL, guild, channel);
    }

    @Nullable
    private static Object readPath(@Nullable Object value, JSONArray path) {
        for (int i = 0; i < path.length() && value != null; i++) {
            value = Reflect.getField(value, path.optString(i));
        }
        return value;
    }

    @Nullable
    static String snowflake(@Nullable Object value) {
        return value instanceof Long ? Long.toUnsignedString((Long) value) : null;
    }

    static boolean eligible(Config.Snapshot snapshot, Kind kind,
            @Nullable String guild, @Nullable String channel, String label) {
        boolean selected = kind == Kind.SERVER ? snapshot.translateServerNames : snapshot.translateChannelNames;
        return selected && snapshot.enabled && snapshot.isUsable()
                && TranslationScope.isValidId(guild) && snapshot.translationScope.allows(guild, channel)
                && validLabel(label, MAX_NAME_CHARACTERS)
                && label.indexOf("://") == -1
                && EnglishDetector.evaluateLabel(label, snapshot.targetLang).shouldTranslate;
    }

    static boolean validLabel(String label, int limit) {
        if (label.trim().isEmpty() || label.codePointCount(0, label.length()) > limit) return false;
        for (int offset = 0; offset < label.length();) {
            int codePoint = label.codePointAt(offset);
            if (Character.isISOControl(codePoint) || codePoint == 0x2028 || codePoint == 0x2029) return false;
            offset += Character.charCount(codePoint);
        }
        return true;
    }

    private static void bindLabel(@Nullable View candidate, HookBridge.TranslationConfiguration config,
            Kind kind, @Nullable String guild, @Nullable String channel) {
        if (!(candidate instanceof TextView)) return;
        TextView view = (TextView) candidate;
        BINDINGS.remove(view);
        CharSequence original = view.getText();
        String source = original == null ? "" : original.toString();
        if (!eligible(config.snapshot, kind, guild, channel, source)) return;

        String key = "ui-label/" + kind + "/" + guild + "/" + channel;
        String configVersion = config.snapshot.version();
        String hash = Scheduler.contentHash("ui-label/" + kind + "/" + source, configVersion);
        Binding binding = new Binding(key, hash, original);
        BINDINGS.put(view, binding);
        hasBindings = true;

        StateStore store = StateStore.get();
        StateStore.Entry entry = store.obtain(key, hash);
        if (entry.status == StateStore.Status.TRANSLATED) {
            render(view, binding, entry.translatedText);
            return;
        }
        String cached = TranslationCache.get().get(hash);
        if (cached != null && store.useCached(entry, cached)) {
            render(view, binding, cached);
            return;
        }
        TranslationTaskId task = store.begin(entry);
        if (task == null) return;
        Scheduler.get().submit(task, source, config.engine,
                id -> current(config.snapshot, configVersion) && store.owns(id),
                (id, accepted, translation, error) -> complete(config.snapshot, configVersion,
                        id, accepted, translation, error));
    }

    private static boolean current(Config.Snapshot admitted, String configVersion) {
        Config.Snapshot latest = HookBridge.get().translationConfiguration().snapshot;
        return HookRuntime.isActive() && latest.enabled && configVersion.equals(latest.version())
                && admitted.translationScope.sameMatchingPolicy(latest.translationScope);
    }

    private static void complete(Config.Snapshot admitted, String configVersion, TranslationTaskId id,
            boolean accepted,
            @Nullable String translation, @Nullable Throwable error) {
        try (HookRuntime.Lease lease = HookRuntime.enter()) {
            if (lease == null) return;
            boolean usable = error == null && translation != null
                    && validLabel(translation, MAX_NAME_CHARACTERS * 2);
            accepted = accepted && current(admitted, configVersion);
            if (!StateStore.get().complete(id, accepted,
                    usable ? translation : null, usable ? null : "Invalid name translation")
                    || !accepted || !usable) return;
            TranslationCache.get().put(id.contentHash, translation);
            for (Map.Entry<TextView, Binding> visible : BINDINGS.entrySet()) {
                if (visible.getValue().accepts(id)) render(visible.getKey(), visible.getValue(), translation);
            }
        }
    }

    private static void render(@Nullable TextView view, Binding binding, @Nullable String translation) {
        if (view == null || translation == null || !TextUtils.equals(view.getText(), binding.original)
                || TextUtils.equals(binding.original, translation)) return;
        binding.rendered = translation;
        view.setText(translation);
    }

    public static boolean reset(long timeoutMs) {
        return !hasBindings || Scheduler.MainThread.runAndAwait(() -> {
            for (Map.Entry<TextView, Binding> visible : BINDINGS.entrySet()) {
                TextView view = visible.getKey();
                Binding binding = visible.getValue();
                if (view != null && binding.rendered != null
                        && TextUtils.equals(view.getText(), binding.rendered)) {
                    view.setText(binding.original);
                }
            }
            BINDINGS.clear();
            hasBindings = false;
        }, timeoutMs);
    }
}
