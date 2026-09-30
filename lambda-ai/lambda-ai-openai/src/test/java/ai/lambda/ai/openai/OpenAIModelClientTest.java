package ai.lambda.ai.openai;

import ai.lambda.ai.core.HttpOptions;
import ai.lambda.ai.core.ChatResponse;
import ai.lambda.ai.core.FinishReason;
import ai.lambda.ai.core.Media;
import ai.lambda.ai.core.Message;
import ai.lambda.ai.core.ModelCapabilities;
import ai.lambda.ai.core.ModelUsage;
import ai.lambda.ai.core.Models;
import ai.lambda.ai.core.Role;
import ai.lambda.ai.core.ToolCall;
import ai.lambda.ai.core.ToolSchema;
import ai.lambda.ai.core.UnsupportedMediaException;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the official SDK against a local server that answers like OpenAI's API. */
class OpenAIModelClientTest {

    private static final HttpOptions FAST = new HttpOptions(Duration.ofSeconds(2), Duration.ofSeconds(5), 1, Duration.ofMillis(1));

    private static String completion(String content, String toolCallsJson, String finishReason) {
        return "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"gpt-5\",\"choices\":[{\"index\":0,"
                + "\"message\":{\"role\":\"assistant\",\"content\":" + (content == null ? "null" : JSONObject.quote(content))
                + ",\"refusal\":null" + (toolCallsJson == null ? "" : ",\"tool_calls\":" + toolCallsJson) + "},"
                + "\"logprobs\":null,\"finish_reason\":\"" + finishReason + "\"}],"
                + "\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":5,\"total_tokens\":16}}";
    }

    private static final String DONE = completion("ok", null, "stop");

    private static String chunk(String choiceJson) {
        return "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-5\",\"choices\":["
                + choiceJson + "]}";
    }

    private static OpenAIModelClient client(LocalServer server, String model) {
        return new OpenAIModelClient("k", model, FAST, server.url() + "/v1");
    }

    private static JSONObject body(LocalServer server) {
        return new JSONObject(server.requests.get(0).text());
    }

    private static JSONArray userParts(LocalServer server) {
        return body(server).getJSONArray("messages").getJSONObject(0).getJSONArray("content");
    }

    @Test
    void sendsImagesPdfsAndAudioInOpenAIsFormat() throws Exception {
        try (var server = new LocalServer(r -> LocalServer.Reply.json(DONE))) {
            Message message = Message.user("Describe all of these",
                    Media.of(new byte[]{1, 2}, "image/png"),
                    Media.fromUrl("https://example.com/cat.jpg"),
                    Media.of(new byte[]{3}, "application/pdf").withName("report.pdf"),
                    Media.of(new byte[]{4}, "audio/wav"));

            client(server, "gpt-audio-1.5").chat(List.of(message), List.of());

            JSONArray parts = userParts(server);
            assertEquals("Describe all of these", parts.getJSONObject(0).getString("text"));
            assertEquals("data:image/png;base64,AQI=", parts.getJSONObject(1).getJSONObject("image_url").getString("url"));
            assertEquals("https://example.com/cat.jpg", parts.getJSONObject(2).getJSONObject("image_url").getString("url"));
            JSONObject file = parts.getJSONObject(3).getJSONObject("file");
            assertEquals("report.pdf", file.getString("filename"));
            assertEquals("data:application/pdf;base64,Aw==", file.getString("file_data"));
            JSONObject audio = parts.getJSONObject(4).getJSONObject("input_audio");
            assertEquals("BA==", audio.getString("data"));
            assertEquals("wav", audio.getString("format"));

            LocalServer.Request request = server.requests.get(0);
            assertEquals("Bearer k", request.header("Authorization"));
            assertEquals("/v1/chat/completions", request.path());
            assertEquals("gpt-audio-1.5", body(server).getString("model"));
        }
    }

    @Test
    void rejectsWhatTheModelCannotTakeBeforeSending() throws Exception {
        try (var server = new LocalServer(r -> LocalServer.Reply.json(DONE))) {
            OpenAIModelClient gpt = client(server, "gpt-5");

            var video = assertThrows(UnsupportedMediaException.class, () -> gpt.chat(
                    List.of(Message.user("watch", Media.of(new byte[1], "video/mp4"))), List.of()));
            var audio = assertThrows(UnsupportedMediaException.class, () -> gpt.chat(
                    List.of(Message.user("listen", Media.of(new byte[1], "audio/wav"))), List.of()));
            var ogg = assertThrows(UnsupportedMediaException.class, () -> client(server, "gpt-audio-1.5").chat(
                    List.of(Message.user("listen", Media.of(new byte[1], "audio/ogg"))), List.of()));
            var pdfUrl = assertThrows(UnsupportedMediaException.class, () -> gpt.chat(
                    List.of(Message.user("read", Media.fromUrl("https://example.com/a.pdf"))), List.of()));

            assertTrue(video.getMessage().contains("does not accept video input"), video.getMessage());
            assertTrue(audio.getMessage().contains("does not accept audio input"), audio.getMessage());
            assertTrue(ogg.getMessage().contains("use WAV or MP3"), ogg.getMessage());
            assertNotNull(pdfUrl.getMessage());
            assertTrue(server.requests.isEmpty());
        }
    }

    @Test
    void textDocumentsAreSentAsText() throws Exception {
        try (var server = new LocalServer(r -> LocalServer.Reply.json(DONE))) {
            Message message = Message.user("Summarize", Media.of("line one".getBytes(StandardCharsets.UTF_8), "text/plain").withName("notes.txt"));

            client(server, "gpt-5").chat(List.of(message), List.of());

            assertEquals("[notes.txt]\nline one", userParts(server).getJSONObject(1).getString("text"));
        }
    }

    @Test
    void sendsTheConversationAndToolsAndReadsToolCalls() throws Exception {
        String toolCalls = "[{\"id\":\"c2\",\"type\":\"function\",\"function\":{\"name\":\"add_todo\",\"arguments\":\"{\\\"task\\\":\\\"eggs\\\"}\"}}]";
        try (var server = new LocalServer(r -> LocalServer.Reply.json(completion(null, toolCalls, "tool_calls")))) {
            List<Message> history = List.of(
                    new Message(Role.SYSTEM, "Be brief.", null),
                    new Message(Role.USER, "add milk", null),
                    new Message(Role.ASSISTANT, "", null, null, List.of(new ToolCall("c1", "add_todo", "{\"task\":\"milk\"}"))),
                    new Message(Role.TOOL, "Added", "c1", "add_todo", null),
                    new Message(Role.ASSISTANT, "Done.", null));
            ToolSchema schema = new ToolSchema("add_todo", "Adds a todo",
                    "{\"type\":\"object\",\"properties\":{\"task\":{\"type\":\"string\"}},\"required\":[\"task\"]}");

            ChatResponse response = client(server, "gpt-5").chat(history, List.of(schema));

            JSONArray messages = body(server).getJSONArray("messages");
            assertEquals(List.of("system", "user", "assistant", "tool", "assistant"),
                    messages.toList().stream().map(m -> ((java.util.Map<?, ?>) m).get("role")).toList());
            JSONObject assistant = messages.getJSONObject(2);
            assertFalse(assistant.has("content") && !assistant.isNull("content"), "a tool-call turn sends no text");
            JSONObject call = assistant.getJSONArray("tool_calls").getJSONObject(0);
            assertEquals("c1", call.getString("id"));
            assertEquals("{\"task\":\"milk\"}", call.getJSONObject("function").getString("arguments"));
            assertEquals("c1", messages.getJSONObject(3).getString("tool_call_id"));
            assertEquals("Added", messages.getJSONObject(3).getString("content"));
            assertEquals("Done.", messages.getJSONObject(4).getString("content"));

            JSONObject function = body(server).getJSONArray("tools").getJSONObject(0).getJSONObject("function");
            assertEquals("add_todo", function.getString("name"));
            assertEquals("Adds a todo", function.getString("description"));
            assertEquals("string", function.getJSONObject("parameters").getJSONObject("properties").getJSONObject("task").getString("type"));
            assertEquals("task", function.getJSONObject("parameters").getJSONArray("required").getString(0));

            assertEquals(1, response.getToolCalls().size());
            assertEquals("c2", response.getToolCalls().get(0).getId());
            assertEquals("add_todo", response.getToolCalls().get(0).getName());
            assertEquals("{\"task\":\"eggs\"}", response.getToolCalls().get(0).getArgumentsJson());
            assertEquals(response.getToolCalls(), response.getAssistantMessage().getToolCalls());
            assertEquals("", response.getAssistantMessage().getContent());
            assertEquals(FinishReason.TOOL_CALLS, response.getFinishReason());
            assertEquals(new ModelUsage(11, 5, 16), response.getUsage());
        }
    }

    @Test
    void readsATextAnswer() throws Exception {
        try (var server = new LocalServer(r -> LocalServer.Reply.json(completion("Hello!", null, "length")))) {
            ChatResponse response = client(server, "gpt-5").chat(List.of(new Message(Role.USER, "hi", null)), null);

            assertEquals("Hello!", response.getAssistantMessage().getContent());
            assertTrue(response.getToolCalls().isEmpty());
            assertEquals(FinishReason.LENGTH, response.getFinishReason());
            assertFalse(body(server).has("tools"));
        }
    }

    @Test
    void streamsTextAndToolCallsAndUsage() throws Exception {
        try (var server = new LocalServer(r -> LocalServer.Reply.sse(
                chunk("{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"Hel\"},\"finish_reason\":null}"),
                chunk("{\"index\":0,\"delta\":{\"content\":\"lo\"},\"finish_reason\":null}"),
                chunk("{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"echo\",\"arguments\":\"{\\\"te\"}}]},\"finish_reason\":null}"),
                chunk("{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"xt\\\":\\\"hi\\\"}\"}}]},\"finish_reason\":null}"),
                chunk("{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":1,\"id\":\"c2\",\"type\":\"function\",\"function\":{\"name\":\"now\",\"arguments\":\"\"}}]},\"finish_reason\":null}"),
                chunk("{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}"),
                "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-5\",\"choices\":[],"
                        + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":4,\"total_tokens\":7}}",
                "[DONE]"))) {
            List<String> deltas = new ArrayList<>();

            ChatResponse response = client(server, "gpt-5").streamChat(List.of(new Message(Role.USER, "hi", null)), List.of(), deltas::add);

            assertEquals(List.of("Hel", "lo"), deltas);
            assertEquals("Hello", response.getAssistantMessage().getContent());
            assertEquals(2, response.getToolCalls().size());
            assertEquals(new ToolCall("c1", "echo", "{\"text\":\"hi\"}").getArgumentsJson(), response.getToolCalls().get(0).getArgumentsJson());
            assertEquals("echo", response.getToolCalls().get(0).getName());
            assertEquals("c2", response.getToolCalls().get(1).getId());
            assertEquals("{}", response.getToolCalls().get(1).getArgumentsJson(), "a call without arguments gets an empty object");
            assertEquals(FinishReason.TOOL_CALLS, response.getFinishReason());
            assertEquals(new ModelUsage(3, 4, 7), response.getUsage());
            assertTrue(body(server).getBoolean("stream"));
            assertTrue(body(server).getJSONObject("stream_options").getBoolean("include_usage"));
        }
    }

    @Test
    void apiErrorsAreRaisedWithOpenAIsMessage() throws Exception {
        String error = "{\"error\":{\"message\":\"Incorrect API key provided\",\"type\":\"invalid_request_error\",\"code\":\"invalid_api_key\"}}";
        try (var server = new LocalServer(r -> new LocalServer.Reply(401, "application/json", error.getBytes(StandardCharsets.UTF_8)))) {
            RuntimeException e = assertThrows(RuntimeException.class,
                    () -> client(server, "gpt-5").chat(List.of(new Message(Role.USER, "hi", null)), List.of()));

            assertTrue(e.getMessage().contains("Incorrect API key provided"), e.getMessage());
            assertEquals(1, server.requests.size(), "one attempt, as configured");
        }
    }

    @Test
    void retriesTemporaryFailuresAsConfigured() throws Exception {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        try (var server = new LocalServer(r -> calls.incrementAndGet() == 1
                ? new LocalServer.Reply(503, "application/json", "{\"error\":{\"message\":\"busy\"}}".getBytes(StandardCharsets.UTF_8))
                : LocalServer.Reply.json(DONE))) {
            HttpOptions twoAttempts = new HttpOptions(Duration.ofSeconds(2), Duration.ofSeconds(5), 2, Duration.ofMillis(1));

            ChatResponse response = new OpenAIModelClient("k", "gpt-5", twoAttempts, server.url() + "/v1")
                    .chat(List.of(new Message(Role.USER, "hi", null)), List.of());

            assertEquals("ok", response.getAssistantMessage().getContent());
            assertEquals(2, calls.get());
        }
    }

    @Test
    void settingsAndRegistration() {
        assertThrows(IllegalArgumentException.class, () -> new OpenAIModelClient(" ", "gpt-5"));
        OpenAIModelClient gpt = new OpenAIModelClient("k", "gpt-5");
        assertFalse(gpt.capabilities().accepts(ai.lambda.ai.core.Modality.AUDIO));
        assertTrue(new OpenAIModelClient("k", "gpt-audio-1.5").capabilities().accepts(ai.lambda.ai.core.Modality.AUDIO));
        assertEquals(ModelCapabilities.textOnly(), gpt.withCapabilities(ModelCapabilities.textOnly()).capabilities());

        assertTrue(Models.names().contains("openai"), Models.names().toString());
        OpenAIModelClient created = assertInstanceOf(OpenAIModelClient.class, Models.create("chatgpt:gpt-5-mini", "k"));
        assertEquals("gpt-5-mini", created.model());
        assertEquals("gpt-5", ((OpenAIModelClient) Models.create("openai", "k")).model());
    }
}
