package io.github.wxmyyds.discordautotranslate.translate;

import androidx.annotation.Nullable;

import java.util.concurrent.ConcurrentHashMap;

import io.github.wxmyyds.discordautotranslate.core.Config;
import io.github.wxmyyds.discordautotranslate.core.Http;
import io.github.wxmyyds.discordautotranslate.core.Protocols;
import io.github.wxmyyds.discordautotranslate.core.ProjectLog;
import io.github.wxmyyds.discordautotranslate.core.Scheduler;
import io.github.wxmyyds.discordautotranslate.core.Segments;
import io.github.wxmyyds.discordautotranslate.core.TranslationException;

/**
 * JSON 翻译引擎（需求 §5.5）。
 *
 * <p>按配置的供应商协议工作，不绑定任何特定服务商：
 * <ol>
 *   <li>用 {@link Protocols#requestBody} 构造请求体（协议决定格式）。</li>
 *   <li>用 {@link Http} 发送 POST，超时可配；端点由 {@link Protocols#endpoint} 按协议解析。</li>
 *   <li>由 {@link Protocols#createDecoder} 为本次响应选择唯一译文解码器。</li>
 * </ol>
 *
 * <p>默认协议为 OpenAI 兼容 {@code /chat/completions}
 * （见 {@link Config#DEF_PROTOCOL}）。</p>
 *
 * <p>不承诺兼容所有接口：配置不完整或响应结构不匹配时通过
 * {@link TranslationException} 给出明确错误（需求 §5.5）。</p>
 */
public final class JsonApiEngine implements Scheduler.Engine {

    private final Config.Snapshot cfg;
    private final Http http;
    private final ConcurrentHashMap<Thread, Http.Request> active = new ConcurrentHashMap<>();

    public JsonApiEngine(Config.Snapshot cfg) {
        this(cfg, new Http(cfg.timeoutMs));
    }

    JsonApiEngine(Config.Snapshot cfg, Http http) {
        this.cfg = cfg;
        this.http = http;
    }

    /** Non-2xx HTTP failures retain the exact provider body for settings-test display. */
    static final class UpstreamHttpException extends TranslationException {
        final String responseBody;

        UpstreamHttpException(Kind kind, Reason reason, String message, int httpStatus,
                              String responseBody) {
            super(kind, reason, message, httpStatus, null);
            this.responseBody = responseBody;
        }
    }

    /** Decoded exchange metadata stays with the single request owner, including late cancellation. */
    static final class Exchange {
        final String translated;
        final int httpStatus;

        Exchange(String translated, int httpStatus) {
            this.translated = translated;
            this.httpStatus = httpStatus;
        }
    }

    public String translate(String text) throws Exception {
        return translate(text, null);
    }

    @Override
    public String translate(String text, @Nullable String precedingContext) throws Exception {
        return execute(text, true, precedingContext).translated;
    }

    /** Connection probes share all request/decoder ownership, but do not judge translation safety. */
    Exchange execute(String text, boolean validateTranslation) throws Exception {
        return execute(text, validateTranslation, null);
    }

    private Exchange execute(String text, boolean validateTranslation,
                             @Nullable String precedingContext) throws Exception {
        Thread worker = Thread.currentThread();
        Http.Request request = new Http.Request();
        if (active.putIfAbsent(worker, request) != null) {
            throw new IllegalStateException("Worker already owns a request");
        }
        try {
            // Registration precedes validation/body construction. A prior cancel interrupts
            // the worker; a concurrent cancel marks this handle before any connection exists.
            request.checkCancelled();
            if (text == null || text.trim().isEmpty()) {
                throw new TranslationException(TranslationException.Kind.EMPTY, "原文为空，无需翻译");
            }
            Http.Headers headers = validateConfig(cfg);
            Http.Response response = requestOnce(text, precedingContext, request, headers);
            String translated = response.body;
            if (translated == null || translated.trim().isEmpty()) {
                throw new TranslationException(TranslationException.Kind.EMPTY,
                        "提取到的译文为空", response.status, null);
            }
            translated = translated.trim();
            if (validateTranslation) {
                String violation = Segments.plainTextViolation(translated, text);
                if (violation != null) {
                    throw new TranslationException(TranslationException.Kind.PARSE,
                            TranslationException.Reason.TRANSLATION_INVALID,
                            "译文未通过防损坏校验：" + violation, response.status, null);
                }
            }
            request.checkCancelled();
            return new Exchange(translated, response.status);
        } catch (Exception e) {
            request.checkCancelled();
            throw e;
        } finally {
            active.remove(worker, request);
        }
    }

    /** Sends exactly once and retains the decoded response status for post-request validation. */
    private Http.Response requestOnce(String text, @Nullable String precedingContext,
                                      Http.Request request, Http.Headers headers)
            throws Exception {
        // 1) 单一输入按协议构造请求体；已保存提示词也追加原样保留结构标记的规则。
        boolean sse = cfg.streaming;
        String systemPrompt = Config.DEF_SYSTEM_PROMPT.equals(cfg.systemPrompt)
                ? Config.defaultSystemPromptFor(cfg.targetLang) : cfg.systemPrompt;
        String body = Protocols.requestBody(cfg.protocol, text, systemPrompt, cfg.model,
                sse, precedingContext, cfg.glossary);
        buildHeaders(headers);

        // Decoder selection is fixed for this request; no reparsing or mode detection.
        request.checkCancelled();
        String url = Protocols.endpoint(cfg.protocol, cfg.apiUrl);
        Http.Response resp = http.postJson(url, body, headers, request,
                Protocols.createDecoder(cfg.protocol, sse));

        // 3) 状态码归类
        if (!resp.isSuccess()) {
            throw classifyHttpError(resp, precedingContext == null && cfg.glossary.isEmpty());
        }

        // Successful HTTP bodies have already passed the selected protocol decoder.
        return resp;
    }

    /**
     * 取消正在进行中的请求：关闭该线程上的连接，让阻塞读立即返回，
     * 否则热重载/关闭后旧代次的请求会继续跑到超时。
     */
    @Override
    public void cancel(Thread worker) {
        if (worker == null) {
            return;
        }
        Http.Request request = active.get(worker);
        try {
            if (request != null) {
                request.cancel();
            }
        } finally {
            worker.interrupt();
        }
    }

    /**
     * 校验一份配置是否可用于发起请求。
     *
     * <p>抽成静态方法供设置页的离线自检复用，避免两处各写一套校验而产生不一致。
     * 请求体与译文提取由 {@link Protocols} 按协议内置实现，无需再校验模板与路径。</p>
     *
     * @return 已解析的额外请求头，真实请求在同一对象上追加协议认证头
     */
    public static Http.Headers validateConfig(Config.Snapshot cfg) throws TranslationException {
        if (cfg.configError != null) {
            throw new TranslationException(TranslationException.Kind.CONFIG, cfg.configError);
        }
        if (cfg.apiUrl == null || cfg.apiUrl.trim().isEmpty()) {
            throw new TranslationException(TranslationException.Kind.CONFIG, "API URL 未配置");
        }
        String u = cfg.apiUrl.trim().toLowerCase(java.util.Locale.US);
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            throw new TranslationException(TranslationException.Kind.CONFIG,
                    "API URL 必须以 http:// 或 https:// 开头");
        }
        try {
            java.net.URI endpoint = new java.net.URI(Protocols.endpoint(cfg.protocol, cfg.apiUrl));
            if (endpoint.getHost() == null || endpoint.getHost().isEmpty()
                    || endpoint.getPort() < -1 || endpoint.getPort() > 65535) {
                throw new IllegalArgumentException("Invalid HTTP endpoint authority");
            }
            endpoint.toURL();
        } catch (java.net.URISyntaxException | java.net.MalformedURLException
                 | IllegalArgumentException invalid) {
            throw new TranslationException(TranslationException.Kind.CONFIG,
                    "API 基础地址或协议无效", invalid);
        }
        return Http.Headers.parse(cfg.extraHeaders);
    }

    /**
     * 在已校验、已解析的请求头上追加协议头。用户已自定义的同名头不覆盖。
     *
     * <p>OpenAI 系协议用 {@code Authorization: Bearer}；Anthropic Messages
     * 用 {@code x-api-key} 且必须携带版本头 {@code anthropic-version}。</p>
     */
    private void buildHeaders(Http.Headers headers) {
        boolean anthropic = Config.PROTOCOL_ANTHROPIC_MESSAGES.equals(cfg.protocol);
        if (cfg.apiKey != null && !cfg.apiKey.trim().isEmpty()) {
            if (anthropic) {
                if (headers.get("x-api-key") == null) {
                    headers.add("x-api-key", cfg.apiKey.trim());
                }
            } else if (headers.get("Authorization") == null) {
                headers.add("Authorization", "Bearer " + cfg.apiKey.trim());
            }
        }
        if (anthropic && headers.get("anthropic-version") == null) {
            headers.add("anthropic-version", "2023-06-01");
        }
    }

    /** Contextual requests can echo the reference text or glossary in an upstream error. */
    private TranslationException classifyHttpError(Http.Response resp, boolean logResponseBody) {
        int status = resp.status;
        if (resp.body != null && !resp.body.isEmpty()) {
            ProjectLog.warn("api.upstream.error httpStatus=" + status
                    + (logResponseBody ? " body=" + resp.body : ""));
        }
        TranslationException.Kind kind = status == 429
                ? TranslationException.Kind.RATE_LIMIT : TranslationException.Kind.HTTP;
        TranslationException.Reason reason = status == 429
                ? TranslationException.Reason.RATE_LIMITED : TranslationException.Reason.HTTP_ERROR;
        String message = status == 429 ? "限流"
                : status == 401 || status == 403 ? "认证失败（请检查 API 密钥）"
                : status >= 500 ? "服务端错误" : "请求失败";
        return new UpstreamHttpException(kind, reason, message, status,
                resp.body == null ? "" : resp.body);
    }
}
