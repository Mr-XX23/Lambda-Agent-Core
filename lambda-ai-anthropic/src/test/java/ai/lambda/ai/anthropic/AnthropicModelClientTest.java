package ai.lambda.ai.anthropic;

import ai.lambda.ai.core.*;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the client through the official SDK against a local server that plays the Claude API. */
class AnthropicModelClientTest {

    record Request(String path, Map<String, List<String>> headers, String body) {
        JSONObject json() {
            return new JSONObject(body);
        }

        String header(String name) {
            List<String> values = headers.get(name);
            return values == null ? "" : String.join(",", values);
        }
    }

    private HttpServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private Function<Request, String> reply = r -> "";
    private String contentType = "application/json";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            Request request = new Request(exchange.getRequestURI().getPath(), Map.copyOf(exchange.getRequestHeaders()),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            requests.add(request);
            byte[] body = reply.apply(request).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private AnthropicModelClient client(String model) {
        return new AnthropicModelClient(AnthropicOkHttpClient.builder()
                .apiKey("test-key")
                .baseUrl("http://localhost:" + server.getAddress().getPort())
                .maxRetries(0)
                .build(), model);
    }

    private static final String TOOL_REPLY = """
            {"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5",
             "content":[{"type":"thinking","thinking":"","signature":"sig-1"},
                        {"type":"text","text":"Let me check."},
                        {"type":"tool_use","id":"toolu_1","name":"echo","input":{"text":"hi"}}],
             "stop_reason":"tool_use","stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":5}}
            """;

    private static final ToolSchema ECHO = new ToolSchema("echo", "Echoes text",
            "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},\"required\":[\"text\"],\"additionalProperties\":false}");

    @Test
    void buildsTheRequestWithMediaToolsAndSafetyOptions() {
        reply = r -> TOOL_REPLY;
        List<Message> history = List.of(
                new Message(Role.SYSTEM, "Be brief.", null),
                Message.user("Summarize both", Media.of(new byte[]{1, 2}, "image/png"),
                        Media.fromUrl("https://example.com/report.pdf")),
                new Message(Role.ASSISTANT, "", null, null, List.of(
                        new ToolCall("toolu_a", "echo", "{\"text\":\"a\"}"), new ToolCall("toolu_b", "echo", "{\"text\":\"b\"}"))),
                new Message(Role.TOOL, "a", "toolu_a", "echo", null),
                new Message(Role.TOOL, "b", "toolu_b", "echo", null));

        client("claude-opus-5-5").chat(history, List.of(ECHO));

        Request request = requests.get(0);
        assertEquals("/v1/messages", request.path());
        assertTrue(request.header("Anthropic-beta").contains(AnthropicModelClient.THINKING_BINDING_BETA), request.headers().toString());
        assertTrue(request.header("Anthropic-beta").contains(AnthropicModelClient.FALLBACK_BETA));
        JSONObject body = request.json();
        assertEquals("claude-opus-5-5", body.getString("model"));
        assertEquals("Be brief.", body.getString("system"));
        assertEquals("drop_block", body.getJSONObject("thinking").getJSONObject("block_binding").getString("prefix_mismatch_behavior"));
        assertEquals("default", body.getString("fallbacks"));

        JSONArray messages = body.getJSONArray("messages");
        assertEquals(3, messages.length(), "user, assistant, then one user message with both tool results");
        JSONArray user = messages.getJSONObject(0).getJSONArray("content");
        assertEquals("image", user.getJSONObject(0).getString("type"));
        assertEquals("image/png", user.getJSONObject(0).getJSONObject("source").getString("media_type"));
        assertEquals("url", user.getJSONObject(1).getJSONObject("source").getString("type"));
        assertEquals("Summarize both", user.getJSONObject(2).getString("text"), "media before the question");
        JSONArray results = messages.getJSONObject(2).getJSONArray("content");
        assertEquals(2, results.length());
        assertEquals("toolu_b", results.getJSONObject(1).getString("tool_use_id"));

        JSONObject tool = body.getJSONArray("tools").getJSONObject(0);
        assertEquals("echo", tool.getString("name"));
        assertEquals("string", tool.getJSONObject("input_schema").getJSONObject("properties").getJSONObject("text").getString("type"));
        assertFalse(tool.getJSONObject("input_schema").getBoolean("additionalProperties"));
    }

    @Test
    void storesTheTurnAndReplaysItExactly() {
        reply = r -> TOOL_REPLY;
        AnthropicModelClient claude = client("claude-opus-5-5");

        ChatResponse first = claude.chat(List.of(new Message(Role.USER, "echo hi", null)), List.of(ECHO));

        assertEquals("Let me check.", first.getAssistantMessage().getContent());
        assertEquals(List.of("toolu_1"), first.getToolCalls().stream().map(ToolCall::getId).toList());
        assertEquals("{\"text\":\"hi\"}", first.getToolCalls().get(0).getArgumentsJson());
        assertEquals(FinishReason.TOOL_CALLS, first.getFinishReason());
        assertEquals(new ModelUsage(10, 5, 15), first.getUsage());
        assertEquals(AnthropicModelClient.PROVIDER, first.getAssistantMessage().getProviderState().provider());

        claude.chat(List.of(new Message(Role.USER, "echo hi", null), first.getAssistantMessage(),
                new Message(Role.TOOL, "hi", "toolu_1", "echo", null)), List.of(ECHO));

        JSONArray replayed = requests.get(1).json().getJSONArray("messages").getJSONObject(1).getJSONArray("content");
        JSONObject thinking = replayed.getJSONObject(0);
        assertEquals("thinking", thinking.getString("type"));
        assertEquals("sig-1", thinking.getString("signature"), "the signed thinking block goes back unchanged");
        assertEquals("tool_use", replayed.getJSONObject(2).getString("type"));
    }

    @Test
    void streamsTextAndCollectsThinkingAndToolCalls() {
        contentType = "text/event-stream";
        reply = r -> String.join("\n", List.of(
                "event: message_start",
                "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_2\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-opus-5-5\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":7,\"output_tokens\":1}}}",
                "",
                "event: content_block_start",
                "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"\"}}",
                "",
                "event: content_block_delta",
                "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"sig-stream\"}}",
                "",
                "event: content_block_stop",
                "data: {\"type\":\"content_block_stop\",\"index\":0}",
                "",
                "event: content_block_start",
                "data: {\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
                "",
                "event: content_block_delta",
                "data: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hel\"}}",
                "",
                "event: content_block_delta",
                "data: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"lo\"}}",
                "",
                "event: content_block_stop",
                "data: {\"type\":\"content_block_stop\",\"index\":1}",
                "",
                "event: content_block_start",
                "data: {\"type\":\"content_block_start\",\"index\":2,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_9\",\"name\":\"echo\",\"input\":{}}}",
                "",
                "event: content_block_delta",
                "data: {\"type\":\"content_block_delta\",\"index\":2,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"text\\\":\"}}",
                "",
                "event: content_block_delta",
                "data: {\"type\":\"content_block_delta\",\"index\":2,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"\\\"yo\\\"}\"}}",
                "",
                "event: content_block_stop",
                "data: {\"type\":\"content_block_stop\",\"index\":2}",
                "",
                "event: message_delta",
                "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":12}}",
                "",
                "event: message_stop",
                "data: {\"type\":\"message_stop\"}",
                "", ""));
        List<String> deltas = new ArrayList<>();

        ChatResponse response = client("claude-opus-5-5").streamChat(List.of(new Message(Role.USER, "hi", null)), List.of(ECHO), deltas::add);

        assertEquals(List.of("Hel", "lo"), deltas);
        assertEquals("Hello", response.getAssistantMessage().getContent());
        assertEquals("{\"text\":\"yo\"}", response.getToolCalls().get(0).getArgumentsJson());
        assertTrue(response.getAssistantMessage().getProviderState().json().contains("sig-stream"));
        assertTrue(requests.get(0).json().getBoolean("stream"));
    }

    @Test
    void refusalsBecomeContentFilterWithTheExplanation() {
        reply = r -> """
                {"id":"msg_3","type":"message","role":"assistant","model":"claude-opus-5-5","content":[],
                 "stop_reason":"refusal","stop_sequence":null,
                 "stop_details":{"type":"refusal","category":"cyber","explanation":"not able to help with that"},
                 "usage":{"input_tokens":3,"output_tokens":0}}
                """;

        ChatResponse response = client("claude-opus-5-5").chat(List.of(new Message(Role.USER, "x", null)), List.of());

        assertEquals(FinishReason.CONTENT_FILTER, response.getFinishReason());
        assertTrue(response.getAssistantMessage().getContent().startsWith("[Claude declined this request"),
                response.getAssistantMessage().getContent());
        assertTrue(response.getAssistantMessage().getContent().contains("not able to help with that"));
    }

    @Test
    void olderModelsGetNoThinkingOrFallbackOptions() {
        reply = r -> TOOL_REPLY;

        client("claude-haiku-4-5").chat(List.of(new Message(Role.USER, "hi", null)), List.of());

        JSONObject body = requests.get(0).json();
        assertFalse(body.has("thinking"));
        assertFalse(body.has("fallbacks"));
        assertTrue(AnthropicModelClient.supportsAdaptiveThinking("claude-sonnet-5-5"));
        assertTrue(AnthropicModelClient.supportsAdaptiveThinking("claude-opus-4-8"));
        assertFalse(AnthropicModelClient.supportsAdaptiveThinking("claude-opus-4-5"));
        assertFalse(AnthropicModelClient.supportsAdaptiveThinking("claude-3-7-sonnet-latest"));
    }

    @Test
    void optionsCanBeTurnedOffAndEffortSet() {
        reply = r -> TOOL_REPLY;

        client("claude-opus-5-5").withRefusalFallbacks(false).withMismatchedThinkingDropped(false).withEffort("high")
                .chat(List.of(new Message(Role.USER, "hi", null)), List.of());

        JSONObject body = requests.get(0).json();
        assertFalse(body.has("fallbacks"));
        assertFalse(body.has("thinking"));
        assertEquals("high", body.getJSONObject("output_config").getString("effort"));
    }

    @Test
    void unsupportedMediaIsRejectedBeforeSending() {
        AnthropicModelClient claude = client("claude-opus-5-5");

        assertThrows(UnsupportedMediaException.class, () -> claude.chat(
                List.of(Message.user("listen", Media.of(new byte[1], "audio/mpeg"))), List.of()));
        assertThrows(UnsupportedMediaException.class, () -> claude.chat(
                List.of(Message.user("look", Media.of(new byte[1], "image/bmp"))), List.of()));
        assertTrue(requests.isEmpty());
    }

    @Test
    void countsTokensWithTheApi() {
        reply = r -> "{\"input_tokens\":123}";

        assertEquals(123, client("claude-opus-5-5").countTokens(List.of(new Message(Role.USER, "hello", null))));
        assertEquals("/v1/messages/count_tokens", requests.get(0).path());
    }
}
