package ai.lambda.ai.gemini;

import ai.lambda.ai.client.HttpOptions;
import ai.lambda.ai.core.ChatResponse;
import ai.lambda.ai.core.FinishReason;
import ai.lambda.ai.core.Media;
import ai.lambda.ai.core.Message;
import ai.lambda.ai.core.Modality;
import ai.lambda.ai.core.ModelCapabilities;
import ai.lambda.ai.core.ModelUsage;
import ai.lambda.ai.core.Models;
import ai.lambda.ai.core.Role;
import ai.lambda.ai.core.ToolCall;
import ai.lambda.ai.core.ToolSchema;
import ai.lambda.ai.core.UnsupportedMediaException;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the official Gemini SDK against a local server that plays the part of the Gemini API. */
class GeminiModelClientTest {

    private static final HttpOptions FAST = new HttpOptions(Duration.ofSeconds(2), Duration.ofSeconds(2), 3, Duration.ofMillis(1));
    /** Gemini sends thought signatures as base64; this one is "sig-1". */
    private static final String SIGNATURE = Base64.getEncoder().encodeToString("sig-1".getBytes(StandardCharsets.UTF_8));

    private static final String TEXT = "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"ok\"}]},\"finishReason\":\"STOP\"}]}";
    private static final String TEXT_STREAM = sse(TEXT);

    private record Reply(int status, String body, long delayMillis) {
        static Reply ok(String body) {
            return new Reply(200, body, 0);
        }
    }

    private record Request(String uri, String apiKey, String body) {
        JSONObject json() {
            return new JSONObject(body);
        }
    }

    private HttpServer server;
    private ExecutorService executor;
    private final AtomicInteger calls = new AtomicInteger();
    private final List<Request> requests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void serve(IntFunction<Reply> replyForCall) {
        server.createContext("/", exchange -> {
            int n = calls.incrementAndGet();
            requests.add(new Request(exchange.getRequestURI().toString(), exchange.getRequestHeaders().getFirst("x-goog-api-key"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            Reply reply = replyForCall.apply(n);
            try {
                if (reply.delayMillis() > 0) Thread.sleep(reply.delayMillis());
                byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", reply.body().startsWith("data:") ? "text/event-stream" : "application/json");
                exchange.sendResponseHeaders(reply.status(), bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            } catch (IOException | InterruptedException ignored) {
                // The client may have given up (timeout test); nothing to do.
            } finally {
                exchange.close();
            }
        });
    }

    private GeminiModelClient client(HttpOptions options) {
        return new GeminiModelClient("test-key", "gemini-test", options, "http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static String sse(String... events) {
        StringBuilder sb = new StringBuilder();
        for (String e : events) sb.append("data: ").append(e).append("\r\n\r\n");
        return sb.toString();
    }

    private static String error(int code, String message, String status) {
        return new JSONObject().put("error", new JSONObject().put("code", code).put("message", message).put("status", status)).toString();
    }

    private static List<Message> userSays(String text) {
        return List.of(new Message(Role.USER, text, null));
    }

    @Test
    void streamingCollectsTextDeltasAndToolCallsWithTheirSignature() {
        serve(n -> Reply.ok(sse(
                "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"Let me \"}]}}]}",
                "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"thinking...\",\"thought\":true},{\"text\":\"check.\"}]}}]}",
                "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"functionCall\":{\"name\":\"add_todo\",\"args\":{\"task\":\"milk\"}},\"thoughtSignature\":\"" + SIGNATURE + "\"}]},\"finishReason\":\"STOP\"}],"
                        + "\"usageMetadata\":{\"promptTokenCount\":7,\"candidatesTokenCount\":3,\"totalTokenCount\":10}}")));
        List<String> deltas = new ArrayList<>();

        ChatResponse response = client(FAST).streamChat(userSays("add milk"), List.of(), deltas::add);

        assertEquals(List.of("Let me ", "check."), deltas, "thought summaries are not part of the answer");
        assertEquals("Let me check.", response.getAssistantMessage().getContent());
        assertEquals(1, response.getToolCalls().size());
        ToolCall call = response.getToolCalls().get(0);
        assertEquals("add_todo", call.getName());
        assertEquals("milk", new JSONObject(call.getArgumentsJson()).getString("task"));
        assertEquals(SIGNATURE, call.getSignature());
        assertEquals(response.getToolCalls(), response.getAssistantMessage().getToolCalls(),
                "the assistant message carries its tool calls so they are replayed in history");
        assertEquals(FinishReason.TOOL_CALLS, response.getFinishReason());
        assertEquals(new ModelUsage(7, 3, 10), response.getUsage());

        Request request = requests.get(0);
        assertTrue(request.uri().contains("/models/gemini-test:streamGenerateContent"), request.uri());
        assertTrue(request.uri().contains("alt=sse"), request.uri());
        assertEquals("test-key", request.apiKey());
        assertFalse(request.uri().contains("test-key"), "the key is not in the URL");
    }

    @Test
    void replaysToolCallsWithTheirSignatureBeforeTheirResults() {
        serve(n -> Reply.ok(TEXT_STREAM));
        List<Message> history = List.of(
                new Message(Role.SYSTEM, "be nice", null),
                new Message(Role.USER, "add milk and eggs", null),
                new Message(Role.ASSISTANT, "", null, null, List.of(
                        new ToolCall("c1", "add_todo", "{\"task\":\"milk\"}", SIGNATURE),
                        new ToolCall("c2", "add_todo", "{\"task\":\"eggs\"}"))),
                new Message(Role.TOOL, "Added milk", "c1", "add_todo", null),
                new Message(Role.TOOL, "Added eggs", "c2", "add_todo", null));
        List<ToolSchema> tools = List.of(new ToolSchema("add_todo", "Adds a todo",
                "{\"type\":\"object\",\"properties\":{\"task\":{\"type\":\"string\"}},\"additionalProperties\":false}"));

        client(FAST).streamChat(history, tools, null);

        JSONObject body = requests.get(0).json();
        assertEquals("be nice", body.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"));
        JSONArray contents = body.getJSONArray("contents");
        assertEquals(3, contents.length(), "user, model, then one merged user turn with both results");
        assertEquals(List.of("user", "model", "user"), List.of(contents.getJSONObject(0).getString("role"),
                contents.getJSONObject(1).getString("role"), contents.getJSONObject(2).getString("role")));

        JSONArray modelParts = contents.getJSONObject(1).getJSONArray("parts");
        assertEquals(2, modelParts.length());
        assertEquals("add_todo", modelParts.getJSONObject(0).getJSONObject("functionCall").getString("name"));
        assertEquals("milk", modelParts.getJSONObject(0).getJSONObject("functionCall").getJSONObject("args").getString("task"));
        assertEquals(SIGNATURE, modelParts.getJSONObject(0).getString("thoughtSignature"));
        assertFalse(modelParts.getJSONObject(1).has("thoughtSignature"));

        JSONArray resultParts = contents.getJSONObject(2).getJSONArray("parts");
        assertEquals(2, resultParts.length());
        JSONObject firstResult = resultParts.getJSONObject(0).getJSONObject("functionResponse");
        assertEquals("add_todo", firstResult.getString("name"));
        assertEquals("Added milk", firstResult.getJSONObject("response").getString("result"));

        JSONObject declaration = body.getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations").getJSONObject(0);
        assertEquals("add_todo", declaration.getString("name"));
        JSONObject schema = declaration.getJSONObject("parametersJsonSchema");
        assertEquals("string", schema.getJSONObject("properties").getJSONObject("task").getString("type"));
        assertFalse(schema.getBoolean("additionalProperties"), "standard JSON Schema keys are kept");
        assertFalse(declaration.has("parameters"), "only one of parameters / parametersJsonSchema may be sent");
        assertEquals("AUTO", body.getJSONObject("toolConfig").getJSONObject("functionCallingConfig").getString("mode"));
    }

    @Test
    void signaturesSavedByOlderVersionsAreStillSent() {
        assertArrayEquals("sig-1".getBytes(StandardCharsets.UTF_8), GeminiModelClient.signatureBytes(SIGNATURE));
        byte[] awkward = {(byte) 0xfb, (byte) 0xff, (byte) 0xfe};
        assertArrayEquals(awkward, GeminiModelClient.signatureBytes(Base64.getUrlEncoder().encodeToString(awkward)));
    }

    @Test
    void sendsMediaAsInlineDataOrFileData() {
        serve(n -> Reply.ok(TEXT_STREAM));
        Message message = Message.user("Compare these",
                Media.of(new byte[]{1, 2, 3}, "image/png"),
                Media.of(new byte[]{4}, "audio/mpeg"),
                Media.fromUrl("https://generativelanguage.googleapis.com/v1beta/files/abc", "video/mp4"),
                Media.of(new byte[]{5}, "application/pdf"));

        client(FAST).streamChat(List.of(message), List.of(), null);

        JSONArray parts = requests.get(0).json().getJSONArray("contents").getJSONObject(0).getJSONArray("parts");
        assertEquals("Compare these", parts.getJSONObject(0).getString("text"));
        JSONObject image = parts.getJSONObject(1).getJSONObject("inlineData");
        assertEquals("image/png", image.getString("mimeType"));
        assertEquals("AQID", image.getString("data"));
        assertEquals("audio/mpeg", parts.getJSONObject(2).getJSONObject("inlineData").getString("mimeType"));
        JSONObject video = parts.getJSONObject(3).getJSONObject("fileData");
        assertEquals("video/mp4", video.getString("mimeType"));
        assertEquals("https://generativelanguage.googleapis.com/v1beta/files/abc", video.getString("fileUri"));
        assertEquals("application/pdf", parts.getJSONObject(4).getJSONObject("inlineData").getString("mimeType"));
    }

    @Test
    void mediaTheModelCannotTakeFailsBeforeAnyRequest() {
        serve(n -> Reply.ok(TEXT_STREAM));
        GeminiModelClient textOnly = client(FAST).withCapabilities(ModelCapabilities.textOnly());

        UnsupportedMediaException e = assertThrows(UnsupportedMediaException.class, () -> textOnly.streamChat(
                List.of(Message.user("look", Media.of(new byte[]{1}, "image/png"))), List.of(), null));

        assertTrue(e.getMessage().contains("does not accept image input"), e.getMessage());
        assertEquals(0, calls.get());
    }

    @Test
    void retriesAServerErrorThenSucceeds() {
        serve(n -> n == 1 ? new Reply(503, error(503, "overloaded", "UNAVAILABLE"), 0) : Reply.ok(TEXT_STREAM));

        ChatResponse response = client(FAST).streamChat(userSays("hi"), List.of(), null);

        assertEquals("ok", response.getAssistantMessage().getContent());
        assertEquals(2, calls.get());
    }

    @Test
    void givesUpAfterTheConfiguredAttemptsAndReportsTheError() {
        serve(n -> new Reply(503, error(503, "overloaded", "UNAVAILABLE"), 0));

        RuntimeException e = assertThrows(RuntimeException.class, () -> client(FAST).chat(userSays("hi"), List.of()));

        assertTrue(e.getMessage().contains("overloaded"), e.getMessage());
        assertEquals(3, calls.get());
    }

    @Test
    void doesNotRetryClientErrors() {
        serve(n -> new Reply(400, error(400, "missing thought_signature", "INVALID_ARGUMENT"), 0));

        RuntimeException e = assertThrows(RuntimeException.class, () -> client(FAST).chat(userSays("hi"), List.of()));

        assertTrue(e.getMessage().contains("missing thought_signature"), e.getMessage());
        assertEquals(1, calls.get());
    }

    @Test
    void aSingleReplyIsBoundedByTheRequestTimeout() {
        HttpOptions shortTimeout = new HttpOptions(Duration.ofSeconds(2), Duration.ofMillis(300), 1, Duration.ofMillis(1));
        serve(n -> new Reply(200, TEXT, 3_000));
        long start = System.nanoTime();

        assertThrows(RuntimeException.class, () -> client(shortTimeout).chat(userSays("hi"), List.of()));

        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 2_500, "gave up at the timeout");
    }

    @Test
    void chatReadsTextToolCallsAndFinishReason() {
        serve(n -> Reply.ok("""
                {"candidates":[{"content":{"role":"model","parts":[
                  {"text":"Here: "},
                  {"functionCall":{"id":"call-7","name":"list_todos","args":{}},"thoughtSignature":"%s"}]},
                  "finishReason":"STOP"}],
                 "usageMetadata":{"promptTokenCount":4,"candidatesTokenCount":2,"totalTokenCount":6}}
                """.formatted(SIGNATURE)));

        ChatResponse response = client(FAST).chat(userSays("show todos"), List.of());

        ToolCall call = response.getToolCalls().get(0);
        assertEquals("call-7", call.getId(), "Gemini's own call id is kept");
        assertEquals("{}", call.getArgumentsJson());
        assertEquals(SIGNATURE, call.getSignature());
        assertEquals("Here: ", response.getAssistantMessage().getContent());
        assertEquals(FinishReason.TOOL_CALLS, response.getFinishReason());
        assertEquals(new ModelUsage(4, 2, 6), response.getUsage());
        assertTrue(requests.get(0).uri().contains("/models/gemini-test:generateContent"), requests.get(0).uri());
    }

    @Test
    void emptyRepliesAreReported() {
        serve(n -> Reply.ok("{\"candidates\":[]}"));
        RuntimeException e = assertThrows(RuntimeException.class, () -> client(FAST).chat(userSays("hi"), List.of()));
        assertTrue(e.getMessage().contains("no candidates"), e.getMessage());
    }

    @Test
    void countTokensReadsTheTotal() {
        serve(n -> Reply.ok("{\"totalTokens\":42}"));

        assertEquals(42, client(FAST).countTokens(List.of(new Message(Role.SYSTEM, "sys", null), new Message(Role.USER, "hi", null))));
        assertTrue(requests.get(0).uri().contains(":countTokens"), requests.get(0).uri());
    }

    @Test
    void finishReasonsAndUsageMapToTheSharedTypes() {
        assertEquals(FinishReason.STOP, GeminiModelClient.finishReason("STOP", false));
        assertEquals(FinishReason.LENGTH, GeminiModelClient.finishReason("MAX_TOKENS", false));
        assertEquals(FinishReason.CONTENT_FILTER, GeminiModelClient.finishReason("SAFETY", false));
        assertEquals(FinishReason.TOOL_CALLS, GeminiModelClient.finishReason("STOP", true));
        assertEquals(FinishReason.UNKNOWN, GeminiModelClient.finishReason("FINISH_REASON_UNSPECIFIED", false));
    }

    @Test
    void settingsAndRegistration() {
        assertThrows(IllegalArgumentException.class, () -> new GeminiModelClient((String) null, "gemini-3.1-flash"));
        GeminiModelClient gemini = new GeminiModelClient("k", "gemini-3.1-flash");
        assertTrue(gemini.capabilities().accepts(Modality.VIDEO));
        assertEquals(ModelCapabilities.textOnly(), gemini.withCapabilities(ModelCapabilities.textOnly()).capabilities());

        assertTrue(Models.names().contains("gemini"), Models.names().toString());
        GeminiModelClient created = assertInstanceOf(GeminiModelClient.class, Models.create("google:gemini-3.8-pro", "k"));
        assertEquals("gemini-3.8-pro", created.model());
    }
}
