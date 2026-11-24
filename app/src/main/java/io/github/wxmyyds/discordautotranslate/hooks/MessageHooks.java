package io.github.wxmyyds.discordautotranslate.hooks;

import io.github.wxmyyds.discordautotranslate.core.ProjectLog;

import android.text.Editable;
import android.view.View;

import androidx.annotation.Nullable;

import io.github.wxmyyds.discordautotranslate.core.HookConfiguration;
import io.github.wxmyyds.discordautotranslate.core.Reflect;
import io.github.wxmyyds.discordautotranslate.core.StateStore;
import io.github.wxmyyds.discordautotranslate.core.TextExtractor;

import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 根据活动版本配置安装消息绑定、正文渲染和文本转换 Hook。
 * 类名、方法签名及参数位置只保存在 {@code hook-config/<versionCode>.json}；
 * 本类负责稳定的翻译与重渲染行为，句柄立即登记到入口拥有的唯一清理列表。
 */
public final class MessageHooks {

    private static final ThreadLocal<ReplyBinding> REPLY_CONVERSION = new ThreadLocal<>();

    /** Host class/method names only; no arguments or message contents. */
    private static String discordCallers() {
        StringBuilder callers = new StringBuilder();
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            String className = frame.getClassName();
            if (!className.startsWith("com.discord.")) continue;
            if (callers.length() > 0) callers.append(" <- ");
            callers.append(className).append('#').append(frame.getMethodName());
            if (callers.length() >= 700) break;
        }
        return callers.length() == 0 ? "none" : callers.toString();
    }

    private MessageHooks() {
    }

    /** Register each installed hook in the caller's sole ownership list. */
    public static void install(XposedModule module, ClassLoader cl, HookConfiguration config,
                               Set<String> installedHookIds, List<XposedInterface.HookHandle> handles) {
        installBindHook(module, cl, config, handles, installedHookIds);
        installBodyRenderHook(module, cl, config, handles, installedHookIds);
        installTextConversionHook(module, cl, config, handles, installedHookIds);
    }

    // ------------------------------------------------------------------ Hook 1: bind

    private static void installBindHook(XposedModule module, ClassLoader cl,
                                        HookConfiguration config,
                                        List<XposedInterface.HookHandle> out,
                                        Set<String> installedHookIds) {
        if (!config.isEnabled("message.bind")) {
            return;
        }
        Method bind = config.findMethod("message.bind", cl);
        JSONObject spec = config.hook("message.bind");
        if (bind == null || spec == null) {
            return;
        }
        MessageLocation.Reader locationReader = MessageLocation.Reader.create(config,
                bind.getParameterTypes()[spec.optInt("modelArgument", -1)]);
        ProjectLog.infoOnce("message.bind.target", "message.bind target="
                + bind.toGenericString());
        out.add(module.hook(bind)
                .setId("dat.bind")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    try (HookRuntime.Lease lease = HookRuntime.enter()) {
                        if (lease == null) return chain.proceed();
                        // Invalidate old reply consumers before native binding can re-use the row.
                        View oldPreview = replyPreviewTextView(asView(chain.getThisObject()), spec);
                        RerenderRegistry.unregister(oldPreview);
                        HookBridge.get().unbindView(oldPreview);
                        try {
                            bindPre(chain, spec, locationReader);
                        } catch (Throwable t) {
                            ProjectLog.warnOnce("message.bind.exception", "message.bind processing failed with "
                                    + t.getClass().getName());
                        }
                        Object[] bindArgs = chain.getArgs().toArray();
                        ReplyBinding previousReply = REPLY_CONVERSION.get();
                        try {
                            Object model = safeArg(chain, spec.optInt("modelArgument", -1));
                            ReplyBinding referenced = model == null ? null
                                    : ReplyBinding.read(model, spec.optJSONObject("referencedMessage"));
                            if (referenced == null) {
                                REPLY_CONVERSION.remove();
                            } else {
                                REPLY_CONVERSION.set(referenced);
                            }
                        } catch (Throwable t) {
                            REPLY_CONVERSION.remove();
                            ProjectLog.warnOnce("message.bind.replyScope",
                                    "reply conversion scope unavailable errorClass="
                                            + t.getClass().getSimpleName());
                        }
                        Object result;
                        try {
                            result = chain.proceed();
                        } finally {
                            if (previousReply == null) {
                                REPLY_CONVERSION.remove();
                            } else {
                                REPLY_CONVERSION.set(previousReply);
                            }
                        }
                        try {
                            bindPost(chain, bind, spec, bindArgs);
                        } catch (Throwable t) {
                            ProjectLog.warnOnce("message.bind.postException",
                                    "message.bind post-processing failed with "
                                            + t.getClass().getName());
                        }
                        return result;
                    }
                }));
        installedHookIds.add("message.bind");
    }

    /** Establishes the content identity mapping from the configured model argument. */
    private static void bindPre(XposedInterface.Chain chain, JSONObject spec,
                                MessageLocation.Reader locationReader) {
        int modelArgument = spec.optInt("modelArgument", -1);
        if (modelArgument < 0 || modelArgument >= chain.getArgs().size()) {
            return;
        }
        Object model = chain.getArg(modelArgument);
        if (model == null) {
            return;
        }
        rememberReplyBinding(model, spec.optJSONObject("referencedMessage"));
        String idGetter = spec.optString("messageIdGetter", null);
        String contentGetter = spec.optString("contentGetter", null);
        if (idGetter == null || contentGetter == null) {
            return;
        }
        String messageId = Reflect.callString(model, idGetter);
        Object content = Reflect.call(Reflect.findMethodExact(model.getClass(), contentGetter, 0), model);
        boolean validMessageId = messageId != null && HookBridge.looksLikeSnowflake(messageId);
        ProjectLog.infoOnce("message.bind", "message.bind invoked idValid="
                + validMessageId + " contentClass="
                + (content == null ? "null" : content.getClass().getName()));
        if (!validMessageId) {
            return;
        }
        MessageLocation location;
        try {
            location = locationReader.read(model);
        } catch (RuntimeException | Error failure) {
            HookBridge.get().forgetMessageLocation(messageId);
            throw failure;
        }
        HookBridge.get().rememberMessageLocation(messageId, location);
        String revisionGetter = spec.optString("editedTimestampGetter", null);
        if (revisionGetter != null) {
            HookBridge.get().rememberMessageRevision(messageId,
                    ReplyBinding.readEditedTimestamp(model, revisionGetter));
        }
        HookBridge.get().rememberContent(messageId, content);

        // The outer message row owns the canonical identity and edit revision.
        // Body and preview consumers register their exact views separately.
        View rowView = asView(chain.getThisObject());
        if (rowView != null) {
            HookBridge.get().bindView(rowView, messageId);
        }
    }

    /** Establish content identity mappings before the native reply converter runs. */
    private static void rememberReplyBinding(Object model, @Nullable JSONObject spec) {
        ReplyBinding referenced = ReplyBinding.read(model, spec);
        if (referenced == null) {
            return;
        }
        boolean validMessageId = HookBridge.looksLikeSnowflake(referenced.messageId);
        if (validMessageId) {
            HookBridge.get().rememberContent(referenced.messageId, referenced.systemContent);
            HookBridge.get().rememberContent(referenced.messageId, referenced.messageContent);
        }
        ProjectLog.infoOnce("message.bind.replyMapping." + validMessageId + "."
                        + (referenced.systemContent != null) + "." + (referenced.messageContent != null),
                "message.bind referenced preview identity idValid=" + validMessageId
                        + " systemContentPresent=" + (referenced.systemContent != null)
                        + " messageContentPresent=" + (referenced.messageContent != null));
    }

    /** After Discord has bound the preview, register it on the shared translation/render path. */
    private static void bindPost(XposedInterface.Chain chain, Method bind, JSONObject spec,
                                 Object[] bindArgs) {
        int modelArgument = spec.optInt("modelArgument", -1);
        if (modelArgument < 0 || modelArgument >= chain.getArgs().size()) {
            return;
        }
        Object model = chain.getArg(modelArgument);
        View row = asView(chain.getThisObject());
        if (model == null || row == null) {
            return;
        }
        View previewText = replyPreviewTextView(row, spec);
        if (previewText == null) {
            return;
        }

        ReplyBinding replySource = ReplyBinding.read(model,
                spec.optJSONObject("referencedMessage"));
        Object previewObject = Reflect.getField(row, spec.optString("replyPreviewField", null));
        View previewContainer = asView(previewObject);
        boolean previewActive = previewContainer != null
                && previewContainer.getVisibility() == View.VISIBLE;
        // Native preview callbacks use the same configured converter as the body.
        // Only subscribe to the canonical message here. Never overwrite Discord's
        // Drawee builder: it contains the first-line margin reserved for the username.
        RerenderRegistry.unregister(previewText);
        if (!previewActive || replySource == null
                || !HookBridge.looksLikeSnowflake(replySource.messageId)) {
            HookBridge.get().unbindView(previewText);
            return;
        }
        HookBridge bridge = HookBridge.get();
        bridge.bindView(previewText, replySource.messageId);
        RerenderRegistry.register(replySource.messageId, previewText, row, bind, bindArgs);
    }

    @Nullable
    private static View replyPreviewTextView(@Nullable View row, JSONObject spec) {
        String previewField = spec.optString("replyPreviewField", null);
        String bindingField = spec.optString("replyPreviewBindingField", null);
        String textViewField = spec.optString("replyPreviewTextField", null);
        if (previewField == null || bindingField == null || textViewField == null) {
            return null;
        }
        Object preview = Reflect.getField(row, previewField);
        Object binding = Reflect.getField(preview, bindingField);
        return asView(Reflect.getField(binding, textViewField));
    }

    // -------------------------------------------------- Hook 2: 正文渲染入口

    /**
     * Hook 配置选出的正文渲染入口。它提供「视图 + 内容」，
     * 是挂图标、登记重渲染与触发翻译的位置。不做替换（替换统一在文本转换 Hook 完成）。
     */
    private static void installBodyRenderHook(XposedModule module, ClassLoader cl,
                                                  HookConfiguration config,
                                                  List<XposedInterface.HookHandle> out,
                                                  Set<String> installedHookIds) {
        if (!config.isEnabled("message.render")) {
            return;
        }
        Method render = config.findMethod("message.render", cl);
        JSONObject spec = config.hook("message.render");
        if (render == null || spec == null) {
            return;
        }
        ProjectLog.infoOnce("message.render.target", "message.render target="
                + render.toGenericString());
        boolean isStatic = java.lang.reflect.Modifier.isStatic(render.getModifiers());
        String identitySource = spec.optString("identitySource", "");
        if (!"argument".equals(identitySource)) {
            throw new IllegalArgumentException("Unsupported message render identity source");
        }
        int viewArgument = spec.optInt("viewArgument", -1);
        int contentArgument = spec.optInt("contentArgument", -1);
        int messageIdArgument = spec.optInt("messageIdArgument", -1);

        out.add(module.hook(render)
                .setId("dat.messageRender")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    try (HookRuntime.Lease lease = HookRuntime.enter()) {
                        if (lease == null) return chain.proceed();
                        Object viewObject = viewArgument >= 0
                                ? safeArg(chain, viewArgument) : chain.getThisObject();
                        View view = asView(viewObject);
                        Object content = safeArg(chain, contentArgument);
                        InlineIconInjector.HostScope hostScope = InlineIconInjector.enterHost(view, null);
                        try {
                            if (!RenderDispatcher.isRerendering() && view != null) {
                                RerenderRegistry.unregister(view);
                                HookBridge.get().unbindView(view);
                                Object[] renderArgs = chain.getArgs().toArray();
                                Object configuredId = safeArg(chain, messageIdArgument);
                                String messageId = configuredId instanceof String
                                        && HookBridge.looksLikeSnowflake((String) configuredId)
                                        ? (String) configuredId : null;
                                ProjectLog.infoOnce("message.render", "message.render invoked host="
                                        + (view != null) + " idResolved=" + (messageId != null)
                                        + " idSource=" + identitySource
                                        + " contentClass="
                                        + (content == null ? "null" : content.getClass().getName()));
                                if (messageId != null) {
                                    HookBridge.get().bindView(view, messageId);
                                    RerenderRegistry.register(messageId, view,
                                            isStatic ? null : chain.getThisObject(), render, renderArgs);
                                    if (content != null) {
                                        HookBridge.get().rememberContent(messageId, content);
                                        HookBridge.get().onMessageBound(messageId, content);
                                    }
                                }
                            }
                        } catch (Throwable t) {
                            ProjectLog.warnOnce("message.render.exception", "message.render processing failed with "
                                    + t.getClass().getName());
                        }
                        try {
                            return chain.proceed();
                        } finally {
                            hostScope.close();
                        }
                    }
                }));
        installedHookIds.add("message.render");
    }

    // ----------------------------------------------- Hook 3: 文本转换

    /**
     * 配置选定的文本转换入口只负责按内容身份读取已有翻译状态并替换译文；
     * 翻译任务由显式绑定/渲染入口登记，转换阶段不补发翻译请求。
     */
    private static void installTextConversionHook(XposedModule module, ClassLoader cl,
                                               HookConfiguration config,
                                               List<XposedInterface.HookHandle> out,
                                               Set<String> installedHookIds) {
        if (!config.isEnabled("message.textConversion")) {
            return;
        }
        Method conversionMethod = config.findMethod("message.textConversion", cl);
        JSONObject spec = config.hook("message.textConversion");
        if (conversionMethod == null || spec == null) {
            return;
        }
        ProjectLog.infoOnce("message.textConversion.target", "message.textConversion target="
                + conversionMethod.toGenericString());
        int contentArgument = spec.optInt("contentArgument", -1);
        int contextArgument = spec.optInt("contextArgument", -1);
        int messageIdArgument = spec.optInt("messageIdArgument", -1);

        out.add(module.hook(conversionMethod)
                .setId("dat.textConversion")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    try (HookRuntime.Lease lease = HookRuntime.enter()) {
                        if (lease == null) return chain.proceed();
                        Object source = safeArg(chain, contentArgument);
                        Object[] renderArgs = chain.getArgs().toArray();
                        ReplyBinding reply = REPLY_CONVERSION.get();
                        Object configuredId = safeArg(chain, messageIdArgument);
                        boolean replyConversion = reply != null && reply.ownsSource(source);
                        boolean replyBody = reply != null && reply.translatesConversion(source, configuredId);
                        String resolvedMessageId = replyConversion
                                ? reply.matchesConversion(source, configuredId) ? reply.messageId : null
                                : HookBridge.get().resolveMessageId(source);
                        ProjectLog.infoOnce("message.textConversion.idResolved." + (resolvedMessageId != null),
                                "message.textConversion identity resolved=" + (resolvedMessageId != null)
                                        + " sourceClass="
                                        + (source == null ? "null" : source.getClass().getName()));
                        ProjectLog.infoOnce("message.textConversion.callers",
                                "message.textConversion Discord callers="
                                        + discordCallers());
                        boolean replyTranslationApplied = false;
                        TextExtractor.Result extracted = null;
                        try {
                            String messageId = resolvedMessageId;
                            if (messageId != null) {
                                // 内容身份映射由显式绑定/渲染入口建立；此处只读取并应用已有状态。
                                HookBridge.get().rememberContent(messageId, source);

                                HookBridge.ReferencedTranslation reused = replyConversion
                                        ? (replyBody ? HookBridge.get().referencedTranslation(messageId, reply.editedTimestamp) : null)
                                        : HookBridge.get().currentTranslation(messageId, source);
                                StateStore.Entry e = reused == null ? null : reused.state;
                                Object original = reused == null ? source : reused.originalContent;
                                extracted = reused == null ? null : reused.extracted;
                                String translated = (e != null
                                        && e.status == StateStore.Status.TRANSLATED
                                        && !HookBridge.get().showingOriginal(messageId, e)) ? e.translatedText : null;
                                if (translated != null) {
                                    Object replacement = StructurableTextFactory.forPlannedTranslation(
                                            original, translated, extracted);
                                    if (replacement != null) {
                                        ProjectLog.info("message translation replacement applied"
                                                + " route=" + (replyConversion ? "reply-canonical" : "body")
                                                + " sourceClass=" + original.getClass().getName()
                                                + " replacementClass=" + replacement.getClass().getName());
                                        renderArgs[contentArgument] = replacement;
                                        replyTranslationApplied = replyConversion;
                                    } else {
                                        ProjectLog.info("message translation replacement rejected"
                                                + " sourceClass=" + source.getClass().getName());
                                        HookBridge.get().onTranslationRenderRejected(
                                                messageId, translated);
                                    }
                                }
                            }
                        } catch (Throwable t) {
                            ProjectLog.warnOnce("message.textConversion.exception",
                                    "message.textConversion processing failed with "
                                            + t.getClass().getName());
                            renderArgs[contentArgument] = source;
                            replyTranslationApplied = false;
                        }

                        // 宿主渲染只执行一次；图标注入失败时保留原始渲染结果。
                        Object rendered = chain.proceed(renderArgs);
                        if (replyTranslationApplied) {
                            // Native singleLine mode does not remove embedded LF or NewLine
                            // nodes. Project only after canonical alignment and rendering,
                            // before the host adds LeadingMarginSpan(width, 0) and attaches it.
                            // Keep the native builder and its Drawee registries intact.
                            Editable preview = (Editable) rendered;
                            ReplyTextProjection.flattenLineBreaks(preview,
                                    index -> preview.replace(index, index + 1, " "));
                        }
                        // A reference is a consumer only, including native system content.
                        // Never inherit an enclosing body/card icon host for this conversion.
                        if (replyConversion) return rendered;
                        return InlineIconInjector.inject(source, rendered,
                                safeArg(chain, contextArgument), cl, extracted);
                    }
                }));
        installedHookIds.add("message.textConversion");
    }

    @Nullable
    private static Object safeArg(XposedInterface.Chain chain, int index) {
        return index < 0 || index >= chain.getArgs().size() ? null : chain.getArg(index);
    }


    // ------------------------------------------------------------------ 工具

    @Nullable
    private static View asView(@Nullable Object o) {
        return o instanceof View ? (View) o : null;
    }
}
