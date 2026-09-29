package ai.lambda.ai.client;

import ai.lambda.ai.core.*;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs GoogleModelClient against a local HTTP server that plays the part of the Gemini API.
 */
class GoogleModelClientTest {

    private static final HttpOptions FAST = new HttpOptions(
            Duration.ofSeconds(2), Duration.ofSeconds(2), 3, Duration.ofMillis(1));

    private static final String TEXT_STREAM = sse(
            "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"ok\"}]},\"finishReason\":\"STOP\"}]}");

    private record Reply(int status, String body, long delayMillis) {
        static Reply ok(String body) {
            return new Reply(200, body, 0);
        }
    }

    private HttpServer server;
    private ExecutorService executor;
    private final AtomicInteger calls = new AtomicInteger();
    private final List<String> requestBodies = new CopyOnWriteArrayList<>();
    private final List<String> requestUris = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
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
            requestUris.add(exchange.getRequestURI().toString());
            requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            Reply reply = replyForCall.apply(n);
            try {
                if (reply.delayMillis() > 0) Thread.sleep(reply.delayMillis());
                byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
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

    private GoogleModelClient client(HttpOptions options) {
        return new GoogleModelClient("test-key", "gemini-test", options,
                "http://localhost:" + server.getAddress().getPort());
    }

    private static String sse(String... events) {
        StringBuilder sb = new StringBuilder();
        for (String e : events) sb.append("data: ").append(e).append("\n\n");
        return sb.toString();
    }

    private static List<Message> userSays(String text) {
        return List.of(new Message(Role.USER, text, null));
    }

    @Test
    void streamChatCollectsTextDeltasAndToolCallWithSignature() {
        serve(n -> Reply.ok(sse(
                "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"Let me \"}]}}]}",
                "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"check.\"}]}}]}",
                "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"functionCall\":{\"name\":\"add_todo\",\"args\":{\"task\":\"milk\"}},\"thoughtSignature\":\"sig-1\"}]},\"finishReason\":\"STOP\"}]}"
        )));
        List<String> deltas = new ArrayList<>();

        ChatResponse response = client(FAST).streamChat(userSays("add milk"), List.of(), deltas::add);

        assertEquals(List.of("Let me ", "check."), deltas);
        assertEquals("Let me check.", response.getAssistantMessage().getContent());
        assertEquals(1, response.getToolCalls().size());
        ToolCall call = response.getToolCalls().get(0);
        assertEquals("add_todo", call.getName());
        assertEquals("milk", new JSONObject(call.getArgumentsJson()).getString("task"));
        assertEquals("sig-1", call.getSignature());
        assertEquals(response.getToolCalls().size(), response.getAssistantMessage().getToolCalls().size(),
                "the assistant message must carry its tool calls so they are replayed in history");
        assertTrue(requestUris.get(0).contains(":streamGenerateContent?alt=sse&key=test-key"), requestUris.get(0));
    }

    @Test
    void requestReplaysToolCallsWithSignatureBeforeTheirResults() {
        serve(n -> Reply.ok(TEXT_STREAM));
        List<Message> history = List.of(
                new Message(Role.SYSTEM, "be nice", null),
                new Message(Role.USER, "add milk and eggs", null),
                new Message(Role.ASSISTANT, "", null, null, List.of(
                        new ToolCall("c1", "add_todo", "{\"task\":\"milk\"}", "sig-1"),
                        new ToolCall("c2", "add_todo", "{\"task\":\"eggs\"}"))),
                new Message(Role.TOOL, "Added milk", "c1", "add_todo", null),
                new Message(Role.TOOL, "Added eggs", "c2", "add_todo", null)
        );
        List<ToolSchema> tools = List.of(new ToolSchema("add_todo", "Adds a todo",
                "{\"type\":\"object\",\"properties\":{\"task\":{\"type\":\"string\"}}}"));

        client(FAST).streamChat(history, tools, null);

        JSONObject body = new JSONObject(requestBodies.get(0));
        assertEquals("be nice", body.getJSONObject("system_instruction").getJSONArray("parts").getJSONObject(0).getString("text"));

        JSONArray contents = body.getJSONArray("contents");
        assertEquals(3, contents.length(), "user, model, then one merged user turn with both results");
        assertEquals("user", contents.getJSONObject(0).getString("role"));
        assertEquals("model", contents.getJSONObject(1).getString("role"));
        assertEquals("user", contents.getJSONObject(2).getString("role"));

        JSONArray modelParts = contents.getJSONObject(1).getJSONArray("parts");
        assertEquals(2, modelParts.length());
        assertEquals("add_todo", modelParts.getJSONObject(0).getJSONObject("functionCall").getString("name"));
        assertEquals("sig-1", modelParts.getJSONObject(0).getString("thoughtSignature"));
        assertFalse(modelParts.getJSONObject(1).has("thoughtSignature"));

        JSONArray resultParts = contents.getJSONObject(2).getJSONArray("parts");
        assertEquals(2, resultParts.length());
        JSONObject firstResult = resultParts.getJSONObject(0).getJSONObject("functionResponse");
        assertEquals("add_todo", firstResult.getString("name"));
        assertEquals("Added milk", firstResult.getJSONObject("response").getString("result"));

        JSONObject declaration = body.getJSONArray("tools").getJSONObject(0)
                .getJSONArray("functionDeclarations").getJSONObject(0);
        assertEquals("add_todo", declaration.getString("name"));
        assertEquals("string", declaration.getJSONObject("parametersJsonSchema")
                .getJSONObject("properties").getJSONObject("task").getString("type"));
        assertFalse(declaration.has("parameters"), "only one of parameters / parametersJsonSchema may be sent");
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

        JSONArray parts = new JSONObject(requestBodies.get(0)).getJSONArray("contents").getJSONObject(0).getJSONArray("parts");
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
        GoogleModelClient textOnly = client(FAST).withCapabilities(ModelCapabilities.textOnly());

        UnsupportedMediaException e = assertThrows(UnsupportedMediaException.class, () -> textOnly.streamChat(
                List.of(Message.user("look", Media.of(new byte[]{1}, "image/png"))), List.of(), null));

        assertTrue(e.getMessage().contains("does not accept image input"), e.getMessage());
        assertEquals(0, calls.get());
    }

    @Test
    void retriesServerErrorThenSucceeds() {
        serve(n -> n == 1 ? new Reply(503, "{\"error\":\"overloaded\"}", 0) : Reply.ok(TEXT_STREAM));

        ChatResponse response = client(FAST).streamChat(userSays("hi"), List.of(), null);

        assertEquals("ok", response.getAssistantMessage().getContent());
        assertEquals(2, calls.get());
    }

    @Test
    void givesUpAfterMaxAttemptsAndReportsErrorBody() {
        serve(n -> new Reply(503, "{\"error\":\"overloaded\"}", 0));

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> client(FAST).streamChat(userSays("hi"), List.of(), null));

        assertTrue(e.getMessage().contains("503"), e.getMessage());
        assertTrue(e.getMessage().contains("overloaded"), e.getMessage());
        assertEquals(3, calls.get());
    }

    @Test
    void doesNotRetryClientErrors() {
        serve(n -> new Reply(400, "{\"error\":\"missing thought_signature\"}", 0));

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> client(FAST).streamChat(userSays("hi"), List.of(), null));

        assertTrue(e.getMessage().contains("missing thought_signature"), e.getMessage());
        assertEquals(1, calls.get());
    }

    @Test
    void retriesWhenServerIsTooSlow() {
        HttpOptions shortTimeout = new HttpOptions(Duration.ofSeconds(2), Duration.ofMillis(300), 2, Duration.ofMillis(1));
        serve(n -> n == 1 ? new Reply(200, TEXT_STREAM, 2_000) : Reply.ok(TEXT_STREAM));

        ChatResponse response = client(shortTimeout).streamChat(userSays("hi"), List.of(), null);

        assertEquals("ok", response.getAssistantMessage().getContent());
        assertEquals(2, calls.get());
    }

    @Test
    void chatParsesToolCallWithSignature() {
        serve(n -> Reply.ok("""
                {"candidates":[{"content":{"role":"model","parts":[
                  {"functionCall":{"name":"list_todos","args":{}},"thoughtSignature":"sig-9"}]}}]}
                """));

        ChatResponse response = client(FAST).chat(userSays("show todos"), List.of());

        assertEquals("sig-9", response.getToolCalls().get(0).getSignature());
        assertEquals(1, response.getAssistantMessage().getToolCalls().size());
        assertTrue(requestUris.get(0).contains(":generateContent?key=test-key"), requestUris.get(0));
    }

    @Test
    void countTokensReadsTotal() {
        serve(n -> Reply.ok("{\"totalTokens\":42}"));

        assertEquals(42, client(FAST).countTokens(userSays("hi")));
    }
}
