package io.github.wxmyyds.discordautotranslate.hooks;

import io.github.wxmyyds.discordautotranslate.core.ProjectLog;

import android.view.View;

import androidx.annotation.Nullable;

import io.github.wxmyyds.discordautotranslate.core.Config;
import io.github.wxmyyds.discordautotranslate.core.EnglishDetector;
import io.github.wxmyyds.discordautotranslate.core.Scheduler;
import io.github.wxmyyds.discordautotranslate.core.StateStore;
import io.github.wxmyyds.discordautotranslate.core.TextExtractor;
import io.github.wxmyyds.discordautotranslate.core.TranslationCache;
import io.github.wxmyyds.discordautotranslate.core.TranslationException;
import io.github.wxmyyds.discordautotranslate.core.TranslationTaskId;
import io.github.wxmyyds.discordautotranslate.translate.JsonApiEngine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 渲染层与翻译状态之间的桥。
 *
 * <p>职责：
 * <ol>
 *   <li>维护「正文视图 → 消息 ID」与「内容对象 → 消息 ID」两张映射：
 *       RecyclerView 会复用 {@code MessageContentView} 实例，
 *       译文状态绝不能存在 View 上（需求 §风险 2）。</li>
 *   <li>协调唯一翻译生产入口；原生重放由 RenderDispatcher 与订阅表负责。</li>
 *   <li>提供“原文/译文”切换与失败重试入口，供图标点击调用。</li>
 * </ol>
 */
public final class HookBridge {

    /** Discord 消息 ID 是雪花 ID：纯数字，长度约 17–20。 */
    private static final int SNOWFLAKE_MIN = 15;
    private static final int SNOWFLAKE_MAX = 21;

    private static final HookBridge INSTANCE = new HookBridge();

    public static HookBridge get() {
        return INSTANCE;
    }

    /** 正文视图 → 消息 ID（有界，避免列表回收后无限增长）。 */
    private final Map<View, String> viewToMessageId = boundedMap(512);

    /** Bounded server/channel locations; only native message row bindings own these records. */
    private final Map<String, MessageLocation> messageLocations = boundedMap(512);
    private final MessageContext messageContext = new MessageContext();

    void rememberMessageLocation(String messageId, MessageLocation location) {
        messageLocations.put(messageId, location);
    }

    void forgetMessageLocation(String messageId) {
        messageLocations.remove(messageId);
        messageContext.forget(messageId);
    }

    /** One gate for bodies, Embed/Poll slots, retries and async ownership. Unknown location is denied. */
    boolean translationAllowed(String key, Config.Snapshot snapshot) {
        if (!snapshot.enabled || !snapshot.translateMessages
                || snapshot.configError != null) return false;
        MessageLocation location = messageLocations.get(cardRowOf(key));
        return location != null && snapshot.translationScope.allows(location.guildId, location.channelId);
    }

    /** Only native row bindings own edit metadata; source snapshots live with state. */
    private final Map<String, MessageRevision> messageRevisions = boundedMap(512);

    private static final class MessageRevision {
        final String editedTimestamp;

        MessageRevision(@Nullable String editedTimestamp) {
            this.editedTimestamp = editedTimestamp;
        }
    }

    static final class ReferencedTranslation {
        final StateStore.Entry state;
        final Object originalContent;
        final TextExtractor.Result extracted;

        ReferencedTranslation(StateStore.Entry state, Object originalContent, TextExtractor.Result extracted) {
            this.state = state;
            this.originalContent = originalContent;
            this.extracted = extracted;
        }
    }

    /** Only the containing message's own bind may record its edit revision. */
    void rememberMessageRevision(String messageId, @Nullable String editedTimestamp) {
        if (messageId != null) {
            messageRevisions.put(messageId, new MessageRevision(editedTimestamp));
        }
    }

    void rememberCanonicalContent(String messageId, Object content) {
        MessageRevision revision = messageRevisions.get(messageId);
        StateStore.get().rememberSource(messageId, new StateStore.Source(content,
                revision != null, revision == null ? null : revision.editedTimestamp));
    }

    /**
     * A reply is a projection, not a second body. Select the canonical source by
     * message identity and edit revision, then verify that source against state.
     * Never hash the preview text or mutate/create state from it.
     */
    @Nullable
    ReferencedTranslation referencedTranslation(String messageId, @Nullable String editedTimestamp) {
        if (messageId == null) {
            return null;
        }
        StateStore.Source original = StateStore.get().source(messageId);
        MessageRevision current = messageRevisions.get(messageId);
        if (original == null || !original.revisionKnown || current == null
                || !java.util.Objects.equals(original.editedTimestamp, editedTimestamp)
                || !java.util.Objects.equals(current.editedTimestamp, editedTimestamp)) {
            return null;
        }
        return currentTranslation(messageId, original.content);
    }

    /** 按引用身份反查消息 ID；Discord StructurableText 的 equals() 是按内容比较。 */
    private final IdentityMessageMap contentIdentityToMessageId = new IdentityMessageMap();

    static final class TranslationConfiguration {
        final Config.Snapshot snapshot;
        final Scheduler.Engine engine;

        TranslationConfiguration(Config.Snapshot snapshot, Scheduler.Engine engine) {
            this.snapshot = snapshot;
            this.engine = engine;
        }
    }

    private static TranslationConfiguration configure(Config.Snapshot snapshot) {
        return new TranslationConfiguration(snapshot, new JsonApiEngine(snapshot));
    }

    private volatile TranslationConfiguration configuration = configure(Config.read(null));

    TranslationConfiguration translationConfiguration() {
        return configuration;
    }

    private HookBridge() {
    }

    // ---------------------------------------------------------------- 配置与引擎

    /**
     * 更新配置。会影响翻译结果的配置变化（URL/模型/目标语言/协议/提示词/凭据）
     * 或范围匹配策略变化后，递增状态代次使旧译文与在途任务失效。
     * 纯范围备注或规则顺序变化保留状态与任务所有权（需求 §5.6、§5.7）。
     */
    public void updateConfig(Config.Snapshot c) {
        if (c == null) {
            return;
        }
        Config.Snapshot old = configuration.snapshot;
        configuration = configure(c);

        // version() 已包含密钥/额外请求头指纹：凭据变更同样会使缓存与在途结果失效。
        boolean invalidate = !old.version().equals(c.version())
                || !old.translationScope.sameMatchingPolicy(c.translationScope);
        if (invalidate || old.enabled != c.enabled) messageContext.clear();
        if (invalidate) {
            StateStore.get().onNewGeneration();
            TranslationCache.get().clear();
            CardState.clear();
        }
        // Dispatch newly admitted work only after configuration, state, and engine agree.
        Scheduler.get().setMaxConcurrency(c.maxConcurrency);
    }

    // ---------------------------------------------------------------- 视图映射

    /** 记录正文视图当前绑定的消息 ID。列表复用时会被覆盖，始终反映最新绑定。 */
    public void bindView(View contentView, String messageId) {
        if (contentView == null || messageId == null) {
            return;
        }
        viewToMessageId.put(contentView, messageId);
    }

    /** Remove a stale direct binding when a recycled preview has no resolvable message ID. */
    public void unbindView(@Nullable View contentView) {
        if (contentView == null) {
            return;
        }
        viewToMessageId.remove(contentView);
    }

    @Nullable
    public String directMessageIdOf(@Nullable View contentView) {
        return contentView == null ? null : viewToMessageId.get(contentView);
    }

    /** Resolve a content child through its enclosing message row when no direct mapping exists. */
    @Nullable
    public String messageIdOf(@Nullable View contentView) {
        View current = contentView;
        while (current != null) {
            String messageId = viewToMessageId.get(current);
            if (messageId != null) {
                return messageId;
            }
            android.view.ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    /** 清空全部映射。热重载与配置重置时调用。 */
    public void reset() {
        viewToMessageId.clear();
        messageRevisions.clear();
        messageLocations.clear();
        messageContext.clear();
        contentIdentityToMessageId.clear();
        CardState.clear();
    }

    private static <K, V> Map<K, V> boundedMap(int maxEntries) {
        return Collections.synchronizedMap(new LinkedHashMap<K, V>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > maxEntries;
            }
        });
    }

    // ------------------------------------------------------- 内容 → 消息 ID

    /**
     * 记录“内容对象 → 消息 ID”。只能在能拿到权威消息 ID 的地方调用
     * （由版本配置选择的消息绑定与回复绑定 Hook）。
     */
    public void rememberContent(String messageId, @Nullable Object structurableText) {
        if (messageId == null || structurableText == null) {
            return;
        }
        contentIdentityToMessageId.put(structurableText, messageId);
    }

    /**
     * 反查某个 {@code StructurableText} 属于哪条消息。
     *
     * <p>按当前对象身份读取；Embed 在同步绑定作用域内使用已捕获的段键，
     * 其他消费者读取规范身份映射。不得按正文哈希反查消息 ID。</p>
     */
    @Nullable
    public String resolveMessageId(@Nullable Object structurableText) {
        if (structurableText == null) {
            return null;
        }
        InlineIconInjector.HostScope host = InlineIconInjector.currentHost();
        if (host != null && host.ownsCardIdentity()) return host.messageIdOf(structurableText);
        return contentIdentityToMessageId.get(structurableText);
    }

    // ------------------------------------------------------- Embed 状态键

    /** Register every current Embed member before a producer/cache hit can enqueue native replay. */
    String registerEmbed(EmbedBinding binding, java.util.List<String> keys, View view, Object target,
                         java.lang.reflect.Method bind, Object[] args) {
        String primary = null;
        for (int i = 0; i < binding.texts.size(); i++) {
            String key = keys.get(i);
            if (key == null) continue;
            if (primary == null) primary = key;
            rememberContent(key, binding.texts.get(i));
            if (!RenderDispatcher.isRerendering()) {
                bindView(view, key);
                RerenderRegistry.register(key, view, target, bind, args);
            }
        }
        if (!RenderDispatcher.isRerendering()) {
            if (primary != null) {
                java.util.List<String> members = new java.util.ArrayList<>();
                for (String key : keys) if (key != null) members.add(key);
                CardState.bind(cardIdentityOf(primary), primary, members);
            }
            for (int i = 0; i < keys.size(); i++) {
                if (keys.get(i) != null) onMessageBound(keys.get(i), binding.texts.get(i));
            }
        }
        return primary;
    }

    /** Card identity includes the complete captured segment sequence, isolating same-row Embeds. */
    java.util.List<String> embedStateKeys(String rowMessageId, java.util.List<?> texts) {
        if (!looksLikeSnowflake(rowMessageId)) throw new IllegalArgumentException("Invalid Embed row identity");
        java.util.List<String> hashes = new java.util.ArrayList<>(texts.size());
        StringBuilder identity = new StringBuilder();
        Config.Snapshot snapshot = configuration.snapshot;
        for (Object text : texts) {
            TextExtractor.Result ex = TextExtractor.extract(text);
            String hash = ex == null || ex.text.trim().isEmpty() ? null
                    : contentHashOf(ex, snapshot, "");
            hashes.add(hash);
            identity.append(hash == null ? "EMPTY" : hash).append('\u0000');
        }
        String cardHash = Scheduler.contentHash(
                identity.toString(), "embed-card");
        java.util.List<String> keys = new java.util.ArrayList<>(hashes.size());
        for (String hash : hashes) {
            keys.add(hash == null ? null
                    : stateKey(rowMessageId, EMBED_KEY_PREFIX + cardHash + "\u0000", hash));
        }
        return keys;
    }

    /** One current capture feeds identity verification, native reconstruction and icon eligibility. */
    @Nullable
    ReferencedTranslation currentTranslation(String key, @Nullable Object content) {
        if (!scopeAllowsDisplay(key)) return null;
        TextExtractor.Result extracted = TextExtractor.extract(content);
        StateStore.Entry state = matchingState(key, extracted);
        return state == null ? null : new ReferencedTranslation(state, content, extracted);
    }

    @Nullable
    StateStore.Entry matchingState(String key, @Nullable TextExtractor.Result extracted) {
        StateStore.Entry entry = StateStore.get().peek(key);
        return entry != null && extracted != null
                && contentHashOf(extracted, configuration.snapshot, entry.contextHash)
                        .equals(entry.contentHash) ? entry : null;
    }

    /** Poll question/answer slots share the same hash, scheduler and state pipeline. */
    @Nullable
    String pollStateKey(String rowMessageId, String slot, Object structurable) {
        if (!looksLikeSnowflake(rowMessageId) || slot == null || slot.isEmpty()
                || slot.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("Invalid Poll state identity");
        }
        TextExtractor.Result ex = TextExtractor.extract(structurable);
        return ex == null || ex.text.trim().isEmpty() ? null
                : stateKey(rowMessageId, POLL_KEY_PREFIX + slot + "\u0000",
                        contentHashOf(ex, configuration.snapshot, ""));
    }

    static boolean isPollKey(@Nullable String key) {
        return key != null && key.contains(POLL_KEY_PREFIX);
    }

    /** Card segment keys are isolated from canonical body IDs. */
    public static boolean isCardKey(@Nullable String key) {
        return key != null && (key.contains(EMBED_KEY_PREFIX) || key.contains(POLL_KEY_PREFIX));
    }

    /**
     * 取卡片段状态键中的行消息 ID 部分；正文键原样返回。
     *
     * <p>同一张卡片承载同一条消息的多个文本段，
     * 各段键不同但行 ID 相同。复用校验按“行未变即仍有效”放宽，
     * 行 ID 变化（视图被复用到其他消息）则仍然拒绝。</p>
     */
    public static String cardRowOf(@Nullable String key) {
        if (key == null) {
            return key;
        }
        int i = isCardKey(key) ? key.indexOf('\u0000') : -1;
        return i >= 0 ? key.substring(0, i) : key;
    }

    static String cardIdentityOf(String key) {
        return isPollKey(key) ? cardRowOf(key) : key.substring(0, key.lastIndexOf('\u0000'));
    }

    /** Scope also gates consuming retained/cached results; the existing global-off display contract stays. */
    boolean scopeAllowsDisplay(String key) {
        Config.Snapshot snapshot = configuration.snapshot;
        MessageLocation location = messageLocations.get(cardRowOf(key));
        return snapshot.configError == null && snapshot.translateMessages && location != null
                && snapshot.translationScope.allows(location.guildId, location.channelId);
    }

    boolean showingOriginal(String key, StateStore.Entry entry) {
        if (!scopeAllowsDisplay(key)) return true;
        CardState.Card card = isCardKey(key) ? CardState.forKey(key) : null;
        return isCardKey(key) ? card == null || card.showingOriginal() : entry.showingOriginal;
    }

    private static String stateKey(String rowMessageId, String namespace, String contentHash) {
        return rowMessageId + namespace + contentHash;
    }

    /** 与 {@link #onMessageBound} 同口径的内容哈希（含多片段缓存命名空间）。 */
    private static String contentHashOf(TextExtractor.Result ex, Config.Snapshot cfg, String contextHash) {
        String hashInput = ex.segmentCount >= 2 ? ex.text + SEGMENT_CACHE_SUFFIX : ex.text;
        String sourceHash = Scheduler.contentHash(hashInput, cfg.version());
        return contextHash.isEmpty() ? sourceHash : Scheduler.contentHash(sourceHash, contextHash);
    }

    /**
     * 多片段消息附加到缓存键的后缀。
     *
     * <p>含 {@code U+0000} 保证不会与任何真实正文混淆；只有存在 ≥2 个可翻译
     * 片段的消息会带上它，因此单片段消息的缓存与旧版本保持兼容。</p>
     *
     * <p>seg2 隔离 seg1 时期的旧缓存；当前输入继续使用统一的样式与保留叶
     * 位置标记。此后缀只区分多片段命名空间，不改变请求或回填协议。</p>
     */
    private static final String SEGMENT_CACHE_SUFFIX = "\u0000seg2";

    /** Embed 状态键的分隔符；真实消息 ID（雪花 ID）不可能包含 U+0000。 */
    private static final String EMBED_KEY_PREFIX = "\u0000embed\u0000";
    private static final String POLL_KEY_PREFIX = "\u0000poll\u0000";

    // ---------------------------------------------------------------- 翻译流程

    /**
     * 消息绑定后的处理入口：识别英文、查缓存、按需发起翻译。
     */
    public void onMessageBound(String messageId, @Nullable Object structurableText) {
        try (HookRuntime.Lease lease = HookRuntime.enter()) {
            if (lease == null || messageId == null || structurableText == null) return;
            produceTranslation(messageId, structurableText);
        }
    }

    private void produceTranslation(String messageId, Object structurableText) {
        TextExtractor.Result ex = TextExtractor.extract(structurableText);
        String nodeTypes = TextExtractor.typeSummary(structurableText, ex);
        ProjectLog.infoOnce("translation.nodeTypes." + nodeTypes,
                "translation content node types=" + nodeTypes);
        if (ex == null || ex.text.trim().isEmpty()) {
            ProjectLog.warnOnce("translation.noText", "translation skipped: extracted text is empty"
                    + " structurableClass=" + structurableText.getClass().getName());
            return;
        }

        TranslationConfiguration runtime = configuration;
        Config.Snapshot cfg = runtime.snapshot;
        if (!cfg.enabled) {
            ProjectLog.warnOnce("translation.disabled", "translation skipped: auto-translate is disabled");
            return;
        }
        if (!translationAllowed(messageId, cfg)) {
            ProjectLog.infoOnce("translation.scopeDenied", "translation skipped: translation scope denied");
            return;
        }
        // Only a canonical body may add preceding reference text. Card slots and reply
        // projections retain their original request/display ownership.
        MessageLocation location = cfg.contextEnabled && !isCardKey(messageId)
                ? messageLocations.get(messageId) : null;
        String context = location == null ? null
                : messageContext.capture(messageId, location, ex.text, cfg.contextCharacters);
        String contextHash = context == null || context.isEmpty()
                ? "" : Scheduler.contentHash(context, "preceding context");
        String hash = contentHashOf(ex, cfg, contextHash);
        StateStore.Entry entry = StateStore.get().obtain(messageId, hash, contextHash);
        rememberCanonicalContent(messageId, structurableText);
        if (entry.status == StateStore.Status.TRANSLATING
                || entry.status == StateStore.Status.TRANSLATED) {
            return; // 已有在途任务或已有译文，不重复翻译（需求 §5.4 去重）。
        }
        if (entry.status == StateStore.Status.FAILED) {
            // 失败后不自动重试：渲染漏斗会被频繁调用（每次重绘都会经过），
            // 若无条件重试会造成请求风暴。失败后统一由用户点图标重试（需求 §2.6）。
            return;
        }

        // 缓存命中：直接出结果，不发请求（需求 §5.6）。
        String cached = TranslationCache.get().get(hash);
        if (cached != null) {
            if (StateStore.get().useCached(entry, cached)) refreshTranslation(messageId);
            return;
        }

        EnglishDetector.Verdict v = EnglishDetector.evaluate(
                ex.text, ex.textNodeCount, cfg.targetLang);
        if (!v.shouldTranslate) {
            ProjectLog.infoOnce("translation.languageSkip." + v.reason.name(),
                    "translation skipped by language detector"
                    + " reason=" + v.reason.name() + " textNodes=" + ex.textNodeCount
                    + " opaqueNodes=" + ex.opaqueNodeCount);
            return;
        }

        ProjectLog.infoOnce("translation.started", "translation request started"
                + " textNodes=" + ex.textNodeCount + " segments=" + ex.segmentCount);
        TranslationTaskId task = StateStore.get().begin(entry);
        if (task == null) return;
        refreshTranslation(messageId);
        Scheduler.get().submit(task, ex.text, context, runtime.engine,
                identity -> HookRuntime.isActive()
                        && translationAllowed(identity.messageId, configuration.snapshot)
                        && StateStore.get().owns(identity),
                (identity, accepted, translated, error) -> {
                    try (HookRuntime.Lease lease = HookRuntime.enter()) {
                        if (lease != null) onTranslateResult(identity, accepted, translated, error);
                    }
                });
    }

    /** Complete task ownership is validated before every transition, including discarded results. */
    void onTranslateResult(TranslationTaskId task, boolean accepted,
                           @Nullable String translated, @Nullable Throwable error) {
        ProjectLog.info("translation result received accepted=" + accepted
                + " translatedPresent=" + (translated != null && !translated.isEmpty())
                + " errorClass=" + (error == null ? "none" : error.getClass().getSimpleName())
                + " errorKind=" + (error instanceof TranslationException
                        ? ((TranslationException) error).kind().name() : "none")
                + " causeClass=" + (error == null || error.getCause() == null
                        ? "none" : error.getCause().getClass().getSimpleName()));
        boolean admitted = accepted && translationAllowed(task.messageId, configuration.snapshot);
        boolean success = admitted && error == null && translated != null && !translated.isEmpty();
        if (!StateStore.get().complete(task, admitted, translated,
                success ? null : admitted ? describe(error) : null)) return;
        if (success) TranslationCache.get().put(task.contentHash, translated);
        refreshTranslation(task.messageId);
    }

    /** A segment update also refreshes its card's aggregate progress/error icon. */
    private static void refreshTranslation(String key) {
        RerenderRegistry.rerender(key);
        CardState.Card card = CardState.forKey(key);
        if (card != null && card.titleKey != null && !key.equals(card.titleKey))
            RerenderRegistry.rerender(card.titleKey);
    }

    /** 把异常转换为可读且不泄露密钥的描述。 */
    private static String describe(@Nullable Throwable t) {
        if (t == null) {
            return "未知错误";
        }
        if (t instanceof TranslationException) {
            return ((TranslationException) t).userMessage();
        }
        return t.getClass().getSimpleName();
    }

    /** 富文本结构未能按严格规则重建时，将网络成功转换为可见翻译失败。 */
    public void onTranslationRenderRejected(String id, String translated) {
        StateStore.Entry entry = StateStore.get().peek(id);
        if (entry == null || entry.status != StateStore.Status.TRANSLATED
                || !translated.equals(entry.translatedText)) {
            return;
        }
        TranslationCache.get().remove(entry.contentHash);
        if (StateStore.get().rejectRender(id, translated)) {
            ProjectLog.info("translation render rejected: native structure reconstruction failed");
            refreshTranslation(id);
        }
    }

    /** 原文/译文切换：由图标点击触发，不触发新请求（需求 §2.4）。 */
    public void toggleShowingOriginal(String messageId) {
        if (isCardKey(messageId)) {
            CardState.Card card = CardState.toggle(messageId);
            if (card != null) for (String key : card.keys) RerenderRegistry.rerender(key);
            return;
        }
        if (StateStore.get().toggle(messageId)) RerenderRegistry.rerender(messageId);
    }

    /**
     * 行内图标点击的统一入口（需求：成功时切换原文/译文，失败时重试）。
     *
     * <p>由 {@code IconClickSpanFactory} 创建的 span 回调触发。
     * 状态可能因列表复用而变化，因此每次都重新读取当前状态。</p>
     */
    public void onIconClicked(String messageId) {
        if (messageId == null) {
            return;
        }
        if (isCardKey(messageId)) {
            CardState.Card card = CardState.forKey(messageId);
            if (card == null || !messageId.equals(card.titleKey)) return;
            toggleShowingOriginal(messageId);
            // Switching back to translation is the sole user retry action for failed slots.
            if (!card.showingOriginal()) for (String key : card.keys) {
                StateStore.Entry segment = StateStore.get().peek(key);
                if (segment != null && segment.status == StateStore.Status.FAILED) retry(key);
            }
            return;
        }
        StateStore.Entry e = StateStore.get().peek(messageId);
        if (e == null) {
            return;
        }
        switch (e.status) {
            case TRANSLATED:
                if (e.translatedText != null) {
                    toggleShowingOriginal(messageId);
                }
                break;
            case FAILED:
                retry(messageId);
                break;
            default:
                // 翻译中或未开始：忽略，避免重复触发。
                break;
        }
    }

    /** 失败后重试：由图标点击触发（需求 §2.6）。 */
    public void retry(String messageId) {
        if (!HookRuntime.isActive() || !translationAllowed(messageId, configuration.snapshot)) return;
        StateStore.Entry e = StateStore.get().peek(messageId);
        if (e == null) {
            return;
        }
        StateStore.Source original = StateStore.get().source(messageId);
        MessageRevision current = messageRevisions.get(messageId);
        boolean sameRevision = original != null && (!original.revisionKnown
                ? current == null
                : current != null && java.util.Objects.equals(
                        original.editedTimestamp, current.editedTimestamp));
        if (!sameRevision || matchingState(messageId, TextExtractor.extract(original.content)) != e) {
            // A new model may have bound before its body renderer. Do not relabel
            // the previous body's retry with the latest model revision or reset state.
            return;
        }
        Object structurable = original.content;

        if (!StateStore.get().prepareRetry(e)) return;
        refreshTranslation(messageId);

        onMessageBound(messageId, structurable);
    }

    // ---------------------------------------------------------------- 工具

    /** 判断字符串是否符合 Discord 雪花 ID 形态。 */
    public static boolean looksLikeSnowflake(@Nullable String s) {
        if (s == null) {
            return false;
        }
        int n = s.length();
        if (n < SNOWFLAKE_MIN || n > SNOWFLAKE_MAX) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }
}
