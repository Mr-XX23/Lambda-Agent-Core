package ai.lambda.examples.multimodal;

import ai.lambda.ai.anthropic.AnthropicModelClient;
import ai.lambda.ai.core.HttpOptions;
import ai.lambda.ai.core.ChatResponse;
import ai.lambda.ai.core.Media;
import ai.lambda.ai.core.Message;
import ai.lambda.ai.core.ModelClient;
import ai.lambda.ai.core.Role;
import ai.lambda.ai.core.ToolSchema;
import ai.lambda.ai.gemini.GeminiModelClient;
import ai.lambda.ai.openai.OpenAIModelClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The OpenAI, Anthropic and Google SDKs share libraries (Jackson, OkHttp, Kotlin) in different
 * versions. With all three installed in one application, each must still send a request and read
 * the reply. A version clash shows up here, when JSON is written and parsed, not at build time.
 */
class AllProvidersTogetherTest {

    private HttpServer server;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            String path = exchange.getRequestURI().getPath();
            String body;
            if (path.endsWith("/chat/completions")) {
                body = """
                        {"id":"chatcmpl-1","object":"chat.completion","created":1,"model":"gpt-5","choices":[{"index":0,
                         "message":{"role":"assistant","content":"from openai","refusal":null},"logprobs":null,"finish_reason":"stop"}],
                         "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}""";
            } else if (path.endsWith("/messages")) {
                body = """
                        {"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5",
                         "content":[{"type":"text","text":"from anthropic"}],
                         "stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":1,"output_tokens":1}}""";
            } else if (path.contains(":generateContent")) {
                body = """
                        {"candidates":[{"content":{"role":"model","parts":[{"text":"from gemini"}]},"finishReason":"STOP"}],
                         "usageMetadata":{"promptTokenCount":1,"candidatesTokenCount":1,"totalTokenCount":2}}""";
            } else {
                body = "{}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(path.equals("/") ? 404 : 200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void eachOfficialSdkWorksWithTheOthersInstalled() {
        HttpOptions options = new HttpOptions(Duration.ofSeconds(2), Duration.ofSeconds(5), 1, Duration.ofMillis(1));
        ModelClient openai = new OpenAIModelClient("k", "gpt-5", options, url() + "/v1");
        ModelClient gemini = new GeminiModelClient("k", "gemini-3.1-flash", options, url());
        ModelClient anthropic = new AnthropicModelClient(AnthropicOkHttpClient.builder()
                .apiKey("k").baseUrl(url()).maxRetries(0).build(), "claude-opus-5-5");

        List<Message> request = List.of(
                new Message(Role.SYSTEM, "Be brief.", null),
                Message.user("What is in this picture?", Media.of(new byte[]{1, 2, 3}, "image/png")));
        List<ToolSchema> tools = List.of(new ToolSchema("lookup", "Looks something up",
                "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}},\"required\":[\"query\"]}"));

        for (ModelClient client : List.of(openai, gemini, anthropic)) {
            ChatResponse response = client.chat(request, tools);
            assertTrue(response.getAssistantMessage().getContent().startsWith("from "), client.getClass().getSimpleName());
        }
        assertEquals("from openai", openai.chat(request, tools).getAssistantMessage().getContent());
        assertEquals("from gemini", gemini.chat(request, tools).getAssistantMessage().getContent());
        assertEquals("from anthropic", anthropic.chat(request, tools).getAssistantMessage().getContent());
    }
}
