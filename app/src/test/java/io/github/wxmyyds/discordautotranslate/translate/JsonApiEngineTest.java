package io.github.wxmyyds.discordautotranslate.translate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.github.wxmyyds.discordautotranslate.core.Config;
import io.github.wxmyyds.discordautotranslate.core.GlossaryLibrary;
import io.github.wxmyyds.discordautotranslate.core.Http;
import io.github.wxmyyds.discordautotranslate.core.MemoryPrefs;
import io.github.wxmyyds.discordautotranslate.core.TranslationException;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class JsonApiEngineTest {
    private static class FakeConnection extends HttpURLConnection {
        final InputStream input;
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        int status = 200;
        boolean disconnected;

        FakeConnection(String response) throws Exception {
            this(new ByteArrayInputStream(response.getBytes(StandardCharsets.UTF_8)));
        }
        FakeConnection(InputStream input) throws Exception {
            super(new URL("https://fake.invalid/v1"));
            this.input = input;
        }
        @Override public void connect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public void disconnect() { disconnected = true; }
        @Override public int getResponseCode() { return status; }
        @Override public InputStream getInputStream() { return input; }
        @Override public InputStream getErrorStream() { return input; }
        @Override public OutputStream getOutputStream() { return output; }
    }

    private interface Factory { HttpURLConnection open(URL url) throws Exception; }

    /** Access the package-private connection seam without widening the production API. */
    private static Http http(Factory factory) throws Exception {
        Class<?> type = Class.forName(Http.class.getName() + "$ConnectionFactory");
        Object connections = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> factory.open((URL) args[0]));
        Constructor<Http> constructor = Http.class.getDeclaredConstructor(int.class, type);
        constructor.setAccessible(true);
        return constructor.newInstance(1000, connections);
    }

    private static Config.Snapshot config(String protocol, boolean streaming) {
        return Config.read(new MemoryPrefs()
                .put(Config.KEY_API_URL, "https://fake.invalid/v1")
                .put(Config.KEY_API_KEY, "key")
                .put(Config.KEY_PROTOCOL, protocol)
                .put(Config.KEY_STREAMING, streaming));
    }

    @SuppressWarnings("unchecked")
    private static Map<Thread, Http.Request> active(JsonApiEngine engine) throws Exception {
        Field field = JsonApiEngine.class.getDeclaredField("active");
        field.setAccessible(true);
        return (Map<Thread, Http.Request>) field.get(engine);
    }

    @Test
    public void completeBusinessPathUsesSelectedModeAndProtocolHeaders() throws Exception {
        String[] protocols = {Config.PROTOCOL_OPENAI_COMPATIBLE,
                Config.PROTOCOL_ANTHROPIC_MESSAGES, Config.PROTOCOL_OPENAI_RESPONSES};
        for (String protocol : protocols) {
            for (boolean streaming : new boolean[]{true, false}) {
                boolean sse = streaming;
                String body;
                if (Config.PROTOCOL_OPENAI_COMPATIBLE.equals(protocol)) {
                    body = sse ? "data: {\"choices\":[{\"delta\":{\"content\":\" 你好 \"}}]}\n\n"
                            + "data: [DONE]\n\n"
                            : chatJson(" 你好 ");
                } else if (Config.PROTOCOL_ANTHROPIC_MESSAGES.equals(protocol)) {
                    body = sse ? "data: {\"type\":\"content_block_delta\","
                            + "\"delta\":{\"type\":\"text_delta\",\"text\":\" 你好 \"}}\n\n"
                            + "data: {\"type\":\"message_stop\"}\n\n"
                            : anthropicJson(" 你好 ");
                } else {
                    body = sse ? responsesDelta(0, " 你好 ") + responsesCompleted(1, " 你好 ")
                            : responsesJson(" 你好 ");
                }
                FakeConnection connection = new FakeConnection(body);
                AtomicInteger opens = new AtomicInteger();
                JsonApiEngine engine = new JsonApiEngine(config(protocol, streaming), http(url -> {
                    opens.incrementAndGet();
                    assertTrue(url.toString().startsWith("https://fake.invalid/v1/"));
                    return connection;
                }));
                assertEquals("你好", engine.translate("hello"));
                assertEquals(1, opens.get());
                assertTrue(active(engine).isEmpty());
                assertTrue(connection.disconnected);
                JSONObject request = new JSONObject(connection.output.toString("UTF-8"));
                assertEquals(streaming, request.getBoolean("stream"));
                assertEquals(sse ? "text/event-stream" : "application/json",
                        connection.getRequestProperty("Accept"));
                if (Config.PROTOCOL_ANTHROPIC_MESSAGES.equals(protocol)) {
                    assertEquals("key", connection.getRequestProperty("x-api-key"));
                    assertEquals("2023-06-01", connection.getRequestProperty("anthropic-version"));
                } else {
                    assertEquals("Bearer key", connection.getRequestProperty("Authorization"));
                }
            }
        }
    }

    @Test
    public void batchUsesOneHttpExchangeAndReturnsEveryMessageForEachProtocolAndMode()
            throws Exception {
        String first = "Move <keep1/>\nCheck again";
        String second = "Use it now";
        String firstTranslation = "移动<keep1/>\n再次检查";
        String secondTranslation = "现在使用";
        String reply = batchReply(firstTranslation, secondTranslation);
        for (String protocol : new String[]{Config.PROTOCOL_OPENAI_COMPATIBLE,
                Config.PROTOCOL_OPENAI_RESPONSES, Config.PROTOCOL_ANTHROPIC_MESSAGES}) {
            for (boolean streaming : new boolean[]{false, true}) {
                FakeConnection connection = new FakeConnection(providerReply(protocol, streaming, reply));
                AtomicInteger opens = new AtomicInteger();
                JsonApiEngine engine = new JsonApiEngine(config(protocol, streaming), http(url -> {
                    opens.incrementAndGet();
                    return connection;
                }));
                assertEquals(List.of(firstTranslation, secondTranslation),
                        engine.translateBatch(List.of(first, second)));
                assertEquals(1, opens.get());
                JSONObject request = new JSONObject(connection.output.toString("UTF-8"));
                assertEquals(streaming, request.getBoolean("stream"));
                String payload = Config.PROTOCOL_OPENAI_RESPONSES.equals(protocol)
                        ? request.getString("input") : request.getJSONArray("messages")
                        .getJSONObject(Config.PROTOCOL_ANTHROPIC_MESSAGES.equals(protocol) ? 0 : 1)
                        .getString("content");
                JSONArray messages = new JSONObject(payload).getJSONArray("messages");
                assertEquals(2, messages.length());
                assertEquals(first, messages.getJSONObject(0).getString("source"));
                assertEquals(second, messages.getJSONObject(1).getString("source"));
                assertTrue(connection.disconnected);
                assertTrue(active(engine).isEmpty());
            }
        }
    }

    @Test
    public void invalidBatchReplyFailsAllMembersAfterOneRequestWithoutRetry() throws Exception {
        String protocol = Config.PROTOCOL_OPENAI_COMPATIBLE;
        List<String> originals = List.of("Move <keep1/>\nCheck again", "Use it now");
        String goodReply = batchReply("移动<keep1/>\n再次检查", "现在使用");
        String[] responses = {
                // The two results returned under the wrong IDs.
                providerReply(protocol, true, batchReply("现在使用", "移动<keep1/>\n再次检查")),
                // The anchor is present but the line break was removed.
                providerReply(protocol, true, batchReply("移动<keep1/> 再次检查", "现在使用")),
                // Provider closed before the SSE terminal event.
                "data: {\"choices\":[{\"delta\":{\"content\":" + JSONObject.quote(goodReply) + "}}]}\n\n",
                providerReply(protocol, true, goodReply.replace("\"m2\"", "\"m1\""))
        };
        TranslationException.Reason[] reasons = {
                TranslationException.Reason.TRANSLATION_INVALID,
                TranslationException.Reason.TRANSLATION_INVALID,
                TranslationException.Reason.RESPONSE_INCOMPLETE,
                TranslationException.Reason.RESPONSE_INVALID
        };
        for (int i = 0; i < responses.length; i++) {
            FakeConnection connection = new FakeConnection(responses[i]);
            AtomicInteger opens = new AtomicInteger();
            JsonApiEngine engine = new JsonApiEngine(config(protocol, true), http(url -> {
                opens.incrementAndGet();
                return connection;
            }));
            TranslationException failure = assertThrows(TranslationException.class,
                    () -> engine.translateBatch(originals));
            assertEquals(reasons[i], failure.reason());
            assertEquals(1, opens.get());
            assertTrue(connection.disconnected);
            assertTrue(active(engine).isEmpty());
        }
    }

    @Test
    public void batchHttpFailureDoesNotExposeAnEchoOfItsSource() throws Exception {
        String source = "provider echoed private material";
        FakeConnection connection = new FakeConnection(source);
        connection.status = 429;
        AtomicInteger opens = new AtomicInteger();
        JsonApiEngine engine = new JsonApiEngine(config(Config.PROTOCOL_OPENAI_COMPATIBLE, false),
                http(url -> {
                    opens.incrementAndGet();
                    return connection;
                }));

        TranslationException error = assertThrows(TranslationException.class,
                () -> engine.translateBatch(List.of(source, "second message")));
        assertEquals(TranslationException.Reason.RATE_LIMITED, error.reason());
        assertEquals(429, error.httpStatus());
        assertFalse(error instanceof JsonApiEngine.UpstreamHttpException);
        assertFalse(error.getMessage().contains(source));
        assertEquals(1, opens.get());
        assertTrue(active(engine).isEmpty());
    }

    @Test
    public void activeGlossaryAlsoRedactsProviderBodyFromSettingsTests() throws Exception {
        String echo = "provider echoed a glossary term";
        FakeConnection connection = new FakeConnection(echo);
        connection.status = 403;
        GlossaryLibrary.Pack pack = GlossaryLibrary.readPack(new ByteArrayInputStream(
                "{\"name\":\"Test\",\"terms\":[[\"world\",\"世界\"]]}".getBytes(StandardCharsets.UTF_8)));
        Config.Snapshot config = Config.read(new MemoryPrefs()
                .put(Config.KEY_API_URL, "https://fake.invalid/v1")
                .put(Config.KEY_STREAMING, false)
                .put(Config.KEY_GLOSSARY_LIBRARY, GlossaryLibrary.EMPTY.addAndSelect(pack).toJson()));
        JsonApiEngine engine = new JsonApiEngine(config, http(url -> connection));
        ApiTester.Result result = new ApiTester.Call(engine, ApiTester.TestKind.CONNECTION,
                ApiTester.DEFAULT_TEST_TEXT, config).call();
        assertFalse(result.ok);
        assertEquals(403, result.failure.httpStatus);
        assertNull(result.failure.upstreamErrorBody);
        assertTrue(active(engine).isEmpty());
    }

    private static String batchReply(String first, String second) throws Exception {
        return new JSONObject().put("translations", new JSONArray()
                .put(new JSONObject().put("id", "m2").put("translation", second))
                .put(new JSONObject().put("id", "m1").put("translation", first))).toString();
    }

    private static String providerReply(String protocol, boolean streaming, String output) {
        if (Config.PROTOCOL_OPENAI_RESPONSES.equals(protocol)) {
            return streaming ? responsesDelta(0, output) + responsesCompleted(1, output)
                    : responsesJson(output);
        }
        if (Config.PROTOCOL_ANTHROPIC_MESSAGES.equals(protocol)) {
            return streaming ? "data: {\"type\":\"content_block_delta\",\"delta\":{"
                    + "\"type\":\"text_delta\",\"text\":" + JSONObject.quote(output)
                    + "}}\n\ndata: {\"type\":\"message_stop\"}\n\n" : anthropicJson(output);
        }
        return streaming ? "data: {\"choices\":[{\"delta\":{\"content\":"
                + JSONObject.quote(output) + "}}]}\n\ndata: [DONE]\n\n" : chatJson(output);
    }

    @Test
    public void executeConnectionModeAndTranslateShareRequestsButNotTranslationValidation()
            throws Exception {
        for (String protocol : new String[]{Config.PROTOCOL_OPENAI_COMPATIBLE,
                Config.PROTOCOL_OPENAI_RESPONSES, Config.PROTOCOL_ANTHROPIC_MESSAGES}) {
            for (boolean streaming : new boolean[]{false, true}) {
                String text = "<translate>你好</translate> XXXX";
                String body = Config.PROTOCOL_OPENAI_COMPATIBLE.equals(protocol)
                        ? streaming ? "data: {\"choices\":[{\"delta\":{\"content\":"
                                + JSONObject.quote(text) + "}}]}\n\ndata: [DONE]\n\n" : chatJson(text)
                        : Config.PROTOCOL_ANTHROPIC_MESSAGES.equals(protocol)
                        ? streaming ? "data: {\"type\":\"content_block_delta\",\"delta\":{"
                                + "\"type\":\"text_delta\",\"text\":" + JSONObject.quote(text)
                                + "}}\n\ndata: {\"type\":\"message_stop\"}\n\n" : anthropicJson(text)
                        : streaming ? responsesDelta(0, text) + responsesCompleted(1, text)
                                : responsesJson(text);
                java.util.List<FakeConnection> connections = new java.util.ArrayList<>();
                AtomicInteger opens = new AtomicInteger();
                JsonApiEngine engine = new JsonApiEngine(config(protocol, streaming), http(url -> {
                    opens.incrementAndGet();
                    FakeConnection connection = new FakeConnection(body);
                    connections.add(connection);
                    return connection;
                }));
                JsonApiEngine.Exchange exchange = engine.execute("hello", false);
                assertEquals(text, exchange.translated);
                assertEquals(200, exchange.httpStatus);
                assertTrue(active(engine).isEmpty());
                try {
                    engine.translate("hello");
                    fail("translate must enable the shared translation validator");
                } catch (TranslationException failure) {
                    assertEquals(TranslationException.Kind.PARSE, failure.kind());
                    assertEquals(TranslationException.Reason.TRANSLATION_INVALID, failure.reason());
                    assertEquals(200, failure.httpStatus());
                }
                assertEquals(2, opens.get());
                assertTrue(active(engine).isEmpty());
                assertEquals(new JSONObject(connections.get(0).output.toString("UTF-8")).toString(),
                        new JSONObject(connections.get(1).output.toString("UTF-8")).toString());
                for (FakeConnection connection : connections) {
                    assertTrue(connection.disconnected);
                    assertFalse(connection.getUseCaches());
                    assertFalse(connection.getInstanceFollowRedirects());
                }
            }
        }
    }

    private static String chatJson(String text) {
        return "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\","
                + "\"content\":" + JSONObject.quote(text) + "}}]}";
    }

    private static String anthropicJson(String text) {
        return "{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":"
                + JSONObject.quote(text) + "}]}";
    }

    @Test
    public void jsonAbnormalOrMissingCompletionRejectsTextAndCleansUpWithoutRetry() throws Exception {
        String secret = "private body https://private.invalid/?token=secret Bearer private-key";
        for (String protocol : new String[]{Config.PROTOCOL_OPENAI_COMPATIBLE,
                Config.PROTOCOL_ANTHROPIC_MESSAGES}) {
            boolean anthropic = Config.PROTOCOL_ANTHROPIC_MESSAGES.equals(protocol);
            for (Object reason : new Object[]{null, JSONObject.NULL, 42, true, false,
                    new JSONObject(), new JSONArray(), "", "unknown", "length",
                    "max_tokens", "refusal", "tool_calls", "tool_use", "content_filter", secret}) {
                JSONObject response = new JSONObject(anthropic ? anthropicJson(secret) : chatJson(secret));
                JSONObject terminal = anthropic ? response
                        : response.getJSONArray("choices").getJSONObject(0);
                // org.json removes a key for Java null; JSONObject.NULL remains explicit JSON null.
                terminal.put(anthropic ? "stop_reason" : "finish_reason", reason);
                assertJsonFailureCleansUp(protocol, response.toString(), reason instanceof String
                        ? TranslationException.Reason.GENERATION_STOPPED
                        : TranslationException.Reason.RESPONSE_INVALID);
            }
        }
    }

    @Test
    public void jsonErrorsRefusalsAndToolCallsRejectOtherwiseNormalTextAndCleanUp() throws Exception {
        String secret = "private body https://private.invalid/?token=secret Bearer private-key";
        for (String protocol : new String[]{Config.PROTOCOL_OPENAI_COMPATIBLE,
                Config.PROTOCOL_ANTHROPIC_MESSAGES}) {
            String normal = Config.PROTOCOL_ANTHROPIC_MESSAGES.equals(protocol)
                    ? anthropicJson(secret) : chatJson(secret);
            assertJsonFailureCleansUp(protocol, new JSONObject(normal)
                    .put("error", new JSONObject().put("message", secret)).toString());
            assertJsonFailureCleansUp(protocol, new JSONObject(normal).put("type", "error").toString());
        }
        for (String field : new String[]{"refusal", "tool_calls", "function_call"}) {
            JSONObject response = new JSONObject(chatJson(secret));
            response.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                    .put(field, secret);
            assertJsonFailureCleansUp(Config.PROTOCOL_OPENAI_COMPATIBLE, response.toString());
        }
    }

    private static void assertJsonFailureCleansUp(String protocol, String body) throws Exception {
        assertJsonFailureCleansUp(protocol, body, TranslationException.Reason.RESPONSE_INVALID);
    }

    private static void assertJsonFailureCleansUp(String protocol, String body,
                                                 TranslationException.Reason reason) throws Exception {
        FakeConnection connection = new FakeConnection(body);
        AtomicInteger opens = new AtomicInteger();
        JsonApiEngine engine = new JsonApiEngine(config(protocol, false),
                http(url -> { opens.incrementAndGet(); return connection; }));
        try {
            engine.translate("hello");
            fail("JSON failure must not return text to the success/cache callback");
        } catch (TranslationException failure) {
            assertEquals(TranslationException.Kind.PARSE, failure.kind());
            assertEquals(reason, failure.reason());
            assertEquals(200, failure.httpStatus());
            for (String detail : new String[]{"private body", "private.invalid", "private-key"}) {
                assertFalse(failure.getMessage().contains(detail));
                assertFalse(failure.toString().contains(detail));
                assertFalse(failure.userMessage().contains(detail));
            }
            assertTrue(failure.getCause() == null);
        }
        assertEquals(1, opens.get());
        assertTrue(connection.disconnected);
        assertTrue(active(engine).isEmpty());
    }

    private static String responsesJson(String text) {
        return "{\"status\":\"completed\",\"output\":[{\"id\":\"msg_1\",\"type\":\"message\","
                + "\"content\":[{\"type\":\"output_text\",\"text\":" + JSONObject.quote(text) + "}]}]}";
    }

    private static String responsesDelta(int sequence, String text) {
        return "event: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\","
                + "\"sequence_number\":" + sequence + ",\"output_index\":0,\"content_index\":0,"
                + "\"item_id\":\"msg_1\",\"delta\":" + JSONObject.quote(text) + "}\n\n";
    }

    private static String responsesCompleted(int sequence, String text) {
        return "event: response.completed\ndata: {\"type\":\"response.completed\",\"sequence_number\":"
                + sequence + ",\"response\":" + responsesJson(text) + "}\n\n";
    }

    @Test
    public void responsesCompletionStopsReadingBeforeEofAndCleansUp() throws Exception {
        byte[] bytes = (responsesDelta(0, "你好") + responsesCompleted(1, "你好"))
                .getBytes(StandardCharsets.UTF_8);
        AtomicInteger reads = new AtomicInteger();
        FakeConnection connection = new FakeConnection(new InputStream() {
            @Override public int read() throws IOException {
                int index = reads.getAndIncrement();
                if (index >= bytes.length) {
                    throw new IOException("Must not read past response.completed");
                }
                return bytes[index] & 0xff;
            }
        });
        AtomicInteger opens = new AtomicInteger();
        JsonApiEngine engine = new JsonApiEngine(config(Config.PROTOCOL_OPENAI_RESPONSES, true),
                http(url -> {
                    opens.incrementAndGet();
                    assertEquals("https://fake.invalid/v1/responses", url.toString());
                    return connection;
                }));
        assertEquals("你好", engine.translate("hello"));
        assertEquals(bytes.length, reads.get());
        assertEquals(1, opens.get());
        assertTrue(connection.disconnected);
        assertTrue(active(engine).isEmpty());
    }

    @Test
    public void responsesErrorsAndMalformedStreamsRejectPartialTextAndCleanUpOnce() throws Exception {
        String secret = "private body https://private.invalid/?token=secret Bearer private-key";
        String prefix = responsesDelta(0, secret);
        for (String suffix : new String[]{"", "data: [DONE]\n\n", "data: {\n\n",
                "event: error\ndata: " + JSONObject.quote(secret) + "\n\n",
                "data: {\"type\":\"error\",\"message\":" + JSONObject.quote(secret) + "}\n\n",
                "data: {\"type\":\"response.failed\",\"sequence_number\":1,\"response\":{"
                        + "\"error\":{\"message\":" + JSONObject.quote(secret) + "}}}\n\n",
                "data: {\"type\":\"response.incomplete\",\"sequence_number\":1,\"response\":{"
                        + "\"incomplete_details\":{\"reason\":" + JSONObject.quote(secret) + "}}}\n\n",
                responsesCompleted(1, "different final text"),
                responsesCompleted(0, secret)}) {
            FakeConnection connection = new FakeConnection(prefix + suffix);
            AtomicInteger opens = new AtomicInteger();
            JsonApiEngine engine = new JsonApiEngine(config(Config.PROTOCOL_OPENAI_RESPONSES, true),
                    http(url -> { opens.incrementAndGet(); return connection; }));
            try {
                engine.translate("hello");
                fail("Responses failure must reject accumulated text");
            } catch (TranslationException failure) {
                assertEquals(TranslationException.Kind.PARSE, failure.kind());
                assertEquals(200, failure.httpStatus());
                for (String detail : new String[]{secret, "private body", "private.invalid", "private-key"}) {
                    assertFalse(failure.getMessage().contains(detail));
                    assertFalse(failure.toString().contains(detail));
                    assertFalse(failure.userMessage().contains(detail));
                }
            }
            assertEquals(1, opens.get());
            assertTrue(connection.disconnected);
            assertTrue(active(engine).isEmpty());
        }
    }

    @Test
    public void responsesUseStrictUtf8AndSharedHttpByteLimit() throws Exception {
        byte[] prefix = responsesDelta(0, "你好").getBytes(StandardCharsets.UTF_8);
        byte[] invalidUtf8 = java.util.Arrays.copyOf(prefix, prefix.length + 2);
        invalidUtf8[prefix.length] = (byte) 0xc3;
        invalidUtf8[prefix.length + 1] = 0x28;
        byte[] comment = ": 你\n\n".getBytes(StandardCharsets.UTF_8);
        AtomicInteger bytesRead = new AtomicInteger();
        InputStream oversized = new InputStream() {
            @Override public int read() {
                return comment[bytesRead.getAndIncrement() % comment.length] & 0xff;
            }
        };
        InputStream[] inputs = {new ByteArrayInputStream(invalidUtf8), oversized};
        String[] messages = {"响应不是合法 UTF-8", "HTTP response exceeds 1 MiB byte limit"};
        for (int i = 0; i < inputs.length; i++) {
            FakeConnection connection = new FakeConnection(inputs[i]);
            AtomicInteger opens = new AtomicInteger();
            JsonApiEngine engine = new JsonApiEngine(config(Config.PROTOCOL_OPENAI_RESPONSES, true),
                    http(url -> { opens.incrementAndGet(); return connection; }));
            try {
                engine.translate("hello");
                fail("Responses must enforce the shared transport limits");
            } catch (TranslationException failure) {
                assertEquals(TranslationException.Kind.PARSE, failure.kind());
                assertEquals(TranslationException.Reason.RESPONSE_INVALID, failure.reason());
                assertEquals(200, failure.httpStatus());
                assertEquals(messages[i], failure.getMessage());
            }
            assertEquals(1, opens.get());
            assertTrue(connection.disconnected);
            assertTrue(active(engine).isEmpty());
        }
        assertEquals(Http.MAX_RESPONSE_BYTES + 1, bytesRead.get());
    }

    @Test
    public void responsesCancellationAfterTextDeltaDisconnectsAndClearsRegistration() throws Exception {
        byte[] prefix = responsesDelta(0, "你好").getBytes(StandardCharsets.UTF_8);
        CountDownLatch waitingForCompletion = new CountDownLatch(1);
        CountDownLatch disconnectSignal = new CountDownLatch(1);
        FakeConnection connection = new FakeConnection(new InputStream() {
            private int offset;
            @Override public int read() throws IOException {
                if (offset < prefix.length) return prefix[offset++] & 0xff;
                waitingForCompletion.countDown();
                awaitIgnoringInterrupts(disconnectSignal);
                throw new IOException("Disconnected before response.completed");
            }
        }) {
            @Override public void disconnect() {
                super.disconnect();
                disconnectSignal.countDown();
            }
        };
        AtomicInteger opens = new AtomicInteger();
        JsonApiEngine engine = new JsonApiEngine(config(Config.PROTOCOL_OPENAI_RESPONSES, true),
                http(url -> { opens.incrementAndGet(); return connection; }));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { engine.translate("hello"); } catch (Throwable e) { failure.set(e); }
        });
        worker.start();
        try {
            assertTrue(waitingForCompletion.await(5, TimeUnit.SECONDS));
            assertTrue(active(engine).containsKey(worker));
            engine.cancel(worker);
            worker.join(5000);
            assertFalse(worker.isAlive());
            assertCancelled(failure.get(), 200);
            assertEquals(1, opens.get());
            assertTrue(connection.disconnected);
            assertTrue(active(engine).isEmpty());
        } finally {
            engine.cancel(worker);
            worker.join(5000);
        }
    }

    @Test
    public void responsesModeMismatchIsRejectedWithoutRetry() throws Exception {
        for (boolean streaming : new boolean[]{true, false}) {
            String body = streaming ? responsesJson("你好")
                    : responsesDelta(0, "你好") + responsesCompleted(1, "你好");
            FakeConnection connection = new FakeConnection(body);
            AtomicInteger opens = new AtomicInteger();
            JsonApiEngine engine = new JsonApiEngine(config(Config.PROTOCOL_OPENAI_RESPONSES, streaming),
                    http(url -> { opens.incrementAndGet(); return connection; }));
            try {
                engine.translate("hello");
                fail("The selected Responses mode must reject the other response format");
            } catch (TranslationException failure) {
                assertEquals(TranslationException.Kind.PARSE, failure.kind());
                assertEquals(streaming ? TranslationException.Reason.RESPONSE_INCOMPLETE
                        : TranslationException.Reason.RESPONSE_INVALID, failure.reason());
                assertEquals(200, failure.httpStatus());
            }
            assertEquals(1, opens.get());
            assertTrue(connection.disconnected);
            assertTrue(active(engine).isEmpty());
        }
    }

    @Test
    public void validatedExtraHeadersAreUsedWithNativeAuthWithoutOverwritingOverrides()
            throws Exception {
        for (String protocol : new String[]{Config.PROTOCOL_OPENAI_COMPATIBLE,
                Config.PROTOCOL_ANTHROPIC_MESSAGES, Config.PROTOCOL_OPENAI_RESPONSES}) {
            boolean anthropic = Config.PROTOCOL_ANTHROPIC_MESSAGES.equals(protocol);
            for (String extras : new String[]{"", "# comment\nX-Tenant: tenant-value",
                    "X-Tenant: tenant-value\n"
                            + (anthropic ? "X-Api-Key: custom-key\nAnthropic-Version: custom-version"
                            : "authorization: custom-auth")}) {
                Config.Snapshot cfg = Config.read(new MemoryPrefs()
                        .put(Config.KEY_API_URL, "https://fake.invalid/v1")
                        .put(Config.KEY_API_KEY, " key ")
                        .put(Config.KEY_PROTOCOL, protocol)
                        .put(Config.KEY_STREAMING, false)
                        .put(Config.KEY_EXTRA_HEADERS, extras));
                Http.Headers validated = JsonApiEngine.validateConfig(cfg);
                assertTrue(ApiTester.selfCheck(cfg).ok);
                boolean overrides = extras.contains("custom-");
                assertEquals(extras.isEmpty() ? null : "tenant-value", validated.get("X-Tenant"));
                assertEquals(overrides ? (anthropic ? "custom-key" : "custom-auth") : null,
                        validated.get(anthropic ? "x-api-key" : "Authorization"));
                String body = anthropic ? anthropicJson("你好")
                        : Config.PROTOCOL_OPENAI_RESPONSES.equals(protocol)
                        ? responsesJson("你好")
                        : chatJson("你好");
                FakeConnection connection = new FakeConnection(body);
                AtomicInteger opens = new AtomicInteger();
                JsonApiEngine engine = new JsonApiEngine(cfg, http(url -> {
                    opens.incrementAndGet();
                    return connection;
                }));
                assertEquals("你好", engine.translate("hello"));
                assertEquals(1, opens.get());
                assertEquals(extras.isEmpty() ? null : "tenant-value",
                        connection.getRequestProperty("X-Tenant"));
                if (anthropic) {
                    assertEquals(overrides ? "custom-key" : "key",
                            connection.getRequestProperty(overrides ? "X-Api-Key" : "x-api-key"));
                    assertEquals(overrides ? "custom-version" : "2023-06-01",
                            connection.getRequestProperty(overrides
                                    ? "Anthropic-Version" : "anthropic-version"));
                } else {
                    assertEquals(overrides ? "custom-auth" : "Bearer key",
                            connection.getRequestProperty(overrides
                                    ? "authorization" : "Authorization"));
                }
                assertTrue(active(engine).isEmpty());
                assertTrue(connection.disconnected);
            }
        }
    }

    @Test
    public void invalidExtraHeadersFailOfflineAndActualValidationWithoutOpeningIo()
            throws Exception {
        for (String extras : new String[]{"Authorization Bearer private-secret", ": value",
                "Invalid Header: value", "X-Header@: value"}) {
            Config.Snapshot cfg = Config.read(new MemoryPrefs()
                    .put(Config.KEY_API_URL, "https://fake.invalid/v1")
                    .put(Config.KEY_EXTRA_HEADERS, extras));
            assertFalse(ApiTester.selfCheck(cfg).ok);
            String offlineMessage = null;
            try {
                JsonApiEngine.validateConfig(cfg);
                fail("Expected header validation error");
            } catch (TranslationException e) {
                assertEquals(TranslationException.Kind.CONFIG, e.kind());
                offlineMessage = e.getMessage();
                assertFalse(e.userMessage().contains("private-secret"));
            }
            AtomicInteger opens = new AtomicInteger();
            JsonApiEngine engine = new JsonApiEngine(cfg, http(url -> {
                opens.incrementAndGet();
                throw new IOException("Must not open");
            }));
            try {
                engine.translate("hello");
                fail("Expected same header validation error");
            } catch (TranslationException e) {
                assertEquals(TranslationException.Kind.CONFIG, e.kind());
                assertEquals(offlineMessage, e.getMessage());
                assertFalse(e.userMessage().contains("private-secret"));
            }
            assertEquals(0, opens.get());
            assertTrue(active(engine).isEmpty());
        }
    }

    @Test
    public void cancellationAndEmptyInputStillPrecedeUrlAndHeaderValidation() throws Exception {
        Config.Snapshot cfg = Config.read(new MemoryPrefs()
                .put(Config.KEY_API_URL, "invalid-url")
                .put(Config.KEY_EXTRA_HEADERS, "malformed-header"));
        AtomicInteger opens = new AtomicInteger();
        JsonApiEngine engine = new JsonApiEngine(cfg, http(url -> {
            opens.incrementAndGet();
            throw new IOException("Must not open");
        }));
        Thread.currentThread().interrupt();
        try {
            engine.translate(" ");
            fail("Cancellation must precede input and config validation");
        } catch (TranslationException e) {
            assertEquals(TranslationException.Kind.CANCELLED, e.kind());
        } finally {
            Thread.interrupted();
        }
        for (String input : new String[]{null, "", " \n\t "}) {
            try {
                engine.translate(input);
                fail("Empty input must precede config validation");
            } catch (TranslationException e) {
                assertEquals(TranslationException.Kind.EMPTY, e.kind());
            }
        }
        try {
            engine.translate("hello");
            fail("URL validation must precede header validation");
        } catch (TranslationException e) {
            assertEquals(TranslationException.Kind.CONFIG, e.kind());
            assertEquals("API URL 必须以 http:// 或 https:// 开头", e.getMessage());
        }
        assertEquals(0, opens.get());
        assertTrue(active(engine).isEmpty());
    }

    @Test
    public void maliciousStreamingReasonsRejectOnceWithoutExceptionOrUserMessageLeakage()
            throws Exception {
        String privateBody = "private response 正文";
        String privateUrl = "https://private.invalid/v1?token=private-token";
        String privateSecret = "Bearer private-secret";
        String quoted = JSONObject.quote(privateBody + "\n" + privateUrl + "\n" + privateSecret);
        String[] protocols = {Config.PROTOCOL_OPENAI_COMPATIBLE,
                Config.PROTOCOL_ANTHROPIC_MESSAGES};
        String[] bodies = {
                "data: {\"choices\":[{\"delta\":{\"content\":" + quoted + "}}]}\n\n"
                        + "data: {\"choices\":[{\"finish_reason\":" + quoted + "}]}\n\n"
                        + "data: [DONE]\n\n",
                "data: {\"type\":\"content_block_delta\","
                        + "\"delta\":{\"type\":\"text_delta\",\"text\":" + quoted + "}}\n\n"
                        + "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":"
                        + quoted + "}}\n\n"
                        + "data: {\"type\":\"message_stop\"}\n\n"};
        for (int i = 0; i < protocols.length; i++) {
            FakeConnection connection = new FakeConnection(bodies[i]);
            AtomicInteger opens = new AtomicInteger();
            JsonApiEngine engine = new JsonApiEngine(config(protocols[i], true), http(url -> {
                opens.incrementAndGet();
                return connection;
            }));
            try {
                engine.translate("hello");
                fail("Malicious reason must reject accumulated text");
            } catch (TranslationException e) {
                assertEquals(TranslationException.Kind.PARSE, e.kind());
                assertEquals(TranslationException.Reason.GENERATION_STOPPED, e.reason());
                assertEquals(200, e.httpStatus());
                for (String detail : new String[]{privateBody, privateUrl, privateSecret}) {
                    assertFalse(e.getMessage().contains(detail));
                    assertFalse(e.toString().contains(detail));
                    assertFalse(e.userMessage().contains(detail));
                }
                assertTrue(e.getCause() == null);
            }
            assertEquals(1, opens.get());
            assertTrue(connection.disconnected);
            assertTrue(active(engine).isEmpty());
        }
    }

    @Test
    public void rawHttpErrorsAreClassifiedBeforeSuccessParsingWithoutBodyLeakage() throws Exception {
        for (int status : new int[]{302, 400, 401, 403, 404, 429, 500}) {
            FakeConnection connection = new FakeConnection("secret non-SSE body");
            connection.status = status;
            JsonApiEngine engine = new JsonApiEngine(config(Config.PROTOCOL_ANTHROPIC_MESSAGES, true),
                    http(url -> connection));
            try {
                engine.translate("hello");
                fail("Expected HTTP error");
            } catch (TranslationException e) {
                assertEquals(status == 429 ? TranslationException.Kind.RATE_LIMIT
                        : TranslationException.Kind.HTTP, e.kind());
                assertEquals(status == 429 ? TranslationException.Reason.RATE_LIMITED
                        : TranslationException.Reason.HTTP_ERROR, e.reason());
                assertEquals(status, e.httpStatus());
                assertFalse(e.userMessage().contains("secret"));
            }
            assertTrue(active(engine).isEmpty());
        }
    }

    @Test
    public void modeMismatchAndDamagedTextHaveNoRetryOrFallback() throws Exception {
        for (String body : new String[]{chatJson("你好"),
                "data: {\"choices\":[{\"delta\":{\"content\":\"<translate>你好\"}}]}\n\n"
                        + "data: [DONE]\n\n",
                "data: {\"choices\":[{\"delta\":{\"content\":\"XXXXXX\"}}]}\n\n"
                        + "data: [DONE]\n\n"}) {
            AtomicInteger opens = new AtomicInteger();
            FakeConnection connection = new FakeConnection(body);
            JsonApiEngine engine = new JsonApiEngine(config(Config.PROTOCOL_OPENAI_COMPATIBLE, true),
                    http(url -> { opens.incrementAndGet(); return connection; }));
            try {
                engine.translate("hello");
                fail("Expected strict parse rejection");
            } catch (TranslationException e) {
                assertEquals(TranslationException.Kind.PARSE, e.kind());
                assertEquals(200, e.httpStatus());
                assertEquals(body.equals(chatJson("你好"))
                        ? TranslationException.Reason.RESPONSE_INCOMPLETE
                        : TranslationException.Reason.TRANSLATION_INVALID, e.reason());
            }
            assertEquals(1, opens.get());
            assertTrue(active(engine).isEmpty());
        }
    }

    @Test
    public void httpSuccessWithPartialTranslationCanStillFailWithReadTimeout() throws Exception {
        byte[] prefix = ("data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\n\n")
                .getBytes(StandardCharsets.UTF_8);
        FakeConnection connection = new FakeConnection(new InputStream() {
            private int offset;
            @Override public int read() throws IOException {
                if (offset < prefix.length) return prefix[offset++] & 0xff;
                throw new SocketTimeoutException("private response detail");
            }
        });
        AtomicInteger opens = new AtomicInteger();
        JsonApiEngine engine = new JsonApiEngine(config(Config.PROTOCOL_OPENAI_COMPATIBLE, true),
                http(url -> { opens.incrementAndGet(); return connection; }));
        try {
            engine.translate("hello");
            fail("Partial translation without a completed stream must not be accepted");
        } catch (TranslationException failure) {
            assertEquals(TranslationException.Kind.TIMEOUT, failure.kind());
            assertEquals(TranslationException.Reason.REQUEST_TIMEOUT, failure.reason());
            assertEquals(200, failure.httpStatus());
            assertTrue(failure.getCause() instanceof SocketTimeoutException);
            assertFalse(failure.userMessage().contains("private response detail"));
        }
        assertEquals(200, connection.status);
        assertEquals(1, opens.get());
        assertTrue(connection.disconnected);
        assertTrue(active(engine).isEmpty());
    }

    @Test
    public void httpSuccessWithEofBeforeDoneRemainsParseFailureNotTimeout() throws Exception {
        FakeConnection connection = new FakeConnection(
                "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\n\n");
        AtomicInteger opens = new AtomicInteger();
        JsonApiEngine engine = new JsonApiEngine(config(Config.PROTOCOL_OPENAI_COMPATIBLE, true),
                http(url -> { opens.incrementAndGet(); return connection; }));
        try {
            engine.translate("hello");
            fail("Missing protocol end event must reject partial translation");
        } catch (TranslationException failure) {
            assertEquals(TranslationException.Kind.PARSE, failure.kind());
            assertEquals(TranslationException.Reason.RESPONSE_INCOMPLETE, failure.reason());
            assertEquals(200, failure.httpStatus());
        }
        assertEquals(200, connection.status);
        assertEquals(1, opens.get());
        assertTrue(connection.disconnected);
        assertTrue(active(engine).isEmpty());
    }

    @Test
    public void cancelBeforeRegistrationInterruptsWorkerAndPreventsAllIo() throws Exception {
        AtomicInteger opens = new AtomicInteger();
        JsonApiEngine engine = new JsonApiEngine(config(Config.PROTOCOL_OPENAI_COMPATIBLE, true),
                http(url -> { opens.incrementAndGet(); throw new IOException("Must not open"); }));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            // Scheduler cancellation can occur before Engine.translate is entered.
            engine.cancel(Thread.currentThread());
            try { engine.translate("hello"); } catch (Throwable e) { failure.set(e); }
        });
        worker.start();
        worker.join(5000);
        assertFalse(worker.isAlive());
        assertCancelled(failure.get(), 0);
        assertEquals(0, opens.get());
        assertTrue(active(engine).isEmpty());
    }

    @Test
    public void cancelInAttachGapMarksHandleUntilFinallyEvenIfInterruptIsCleared() throws Exception {
        CountDownLatch opened = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FakeConnection connection = new FakeConnection("data: [DONE]\n\n");
        JsonApiEngine engine = new JsonApiEngine(config(Config.PROTOCOL_OPENAI_COMPATIBLE, true),
                http(url -> {
                    opened.countDown();
                    awaitIgnoringInterrupts(release);
                    return connection;
                }));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { engine.translate("hello"); } catch (Throwable e) { failure.set(e); }
        });
        worker.start();
        try {
            assertTrue(opened.await(5, TimeUnit.SECONDS));
            Http.Request registered = active(engine).get(worker);
            assertTrue(registered != null);
            engine.cancel(worker);
            engine.cancel(worker);
            assertTrue(registered.isCancelled());
            assertTrue(active(engine).get(worker) == registered);
            release.countDown();
            worker.join(5000);
            assertFalse(worker.isAlive());
            assertCancelled(failure.get(), 0);
            assertTrue(connection.disconnected);
            assertEquals(0, connection.output.size());
            assertTrue(active(engine).isEmpty());
        } finally {
            release.countDown();
            engine.cancel(worker);
            worker.join(5000);
        }
    }

    @Test
    public void cancellationDuringReadDisconnectsAndLeavesNoWorkerRegistration() throws Exception {
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch disconnectSignal = new CountDownLatch(1);
        FakeConnection connection = new FakeConnection(new InputStream() {
            @Override public int read() throws IOException {
                reading.countDown();
                awaitIgnoringInterrupts(disconnectSignal);
                throw new IOException("Closed");
            }
        }) {
            @Override public void disconnect() {
                super.disconnect();
                disconnectSignal.countDown();
            }
        };
        JsonApiEngine engine = new JsonApiEngine(config(Config.PROTOCOL_OPENAI_COMPATIBLE, true),
                http(url -> connection));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { engine.translate("hello"); } catch (Throwable e) { failure.set(e); }
        });
        worker.start();
        try {
            assertTrue(reading.await(5, TimeUnit.SECONDS));
            engine.cancel(worker);
            worker.join(5000);
            assertFalse(worker.isAlive());
            assertCancelled(failure.get(), 200);
            assertTrue(connection.disconnected);
            assertTrue(active(engine).isEmpty());
        } finally {
            engine.cancel(worker);
            worker.join(5000);
        }
    }

    private static void assertCancelled(Throwable failure, int status) {
        assertTrue(String.valueOf(failure), failure instanceof TranslationException);
        assertEquals(TranslationException.Kind.CANCELLED, ((TranslationException) failure).kind());
        assertEquals(TranslationException.Reason.CANCELLED,
                ((TranslationException) failure).reason());
        assertEquals(status, ((TranslationException) failure).httpStatus());
    }

    private static void awaitIgnoringInterrupts(CountDownLatch latch) throws IOException {
        while (true) {
            try {
                if (!latch.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("Test latch timed out");
                }
                return;
            } catch (InterruptedException ignored) {
                // Deliberately clear interruption: the request handle must still cancel I/O.
            }
        }
    }
}
