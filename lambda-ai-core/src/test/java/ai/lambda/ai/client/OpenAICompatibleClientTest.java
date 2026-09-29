package ai.lambda.ai.client;

import ai.lambda.ai.core.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OpenAICompatibleClientTest {

    private static final HttpOptions FAST = new HttpOptions(Duration.ofSeconds(2), Duration.ofSeconds(5), 1, Duration.ofMillis(1));
    private static final String DONE = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}";

    private static OpenAIModelClient client(LocalServer server, OpenAICompatibleProvider provider, String model) {
        return new OpenAIModelClient(provider.withBaseUrl(server.url() + "/v1"), "k", model, FAST);
    }

    private static JSONArray userParts(LocalServer server) {
        JSONObject body = new JSONObject(server.requests.get(0).text());
        return body.getJSONArray("messages").getJSONObject(0).getJSONArray("content");
    }

    @Test
    void openAiEncodesImagesPdfsAndAudio() throws Exception {
        try (var server = new LocalServer(r -> LocalServer.Reply.json(DONE))) {
            Message message = Message.user("Describe all of these",
                    Media.of(new byte[]{1, 2}, "image/png"),
                    Media.fromUrl("https://example.com/cat.jpg"),
                    Media.of(new byte[]{3}, "application/pdf").withName("report.pdf"),
                    Media.of(new byte[]{4}, "audio/wav"));

            client(server, OpenAICompatibleProvider.OPENAI, "gpt-audio-1.5").chat(List.of(message), List.of());

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
            assertEquals("Bearer k", server.requests.get(0).header("Authorization"));
            assertEquals("/v1/chat/completions", server.requests.get(0).path());
        }
    }

    @Test
    void openAiRejectsWhatItCannotTakeBeforeSending() throws Exception {
        try (var server = new LocalServer(r -> LocalServer.Reply.json(DONE))) {
            OpenAIModelClient gpt = client(server, OpenAICompatibleProvider.OPENAI, "gpt-5");

            var video = assertThrows(UnsupportedMediaException.class, () -> gpt.chat(
                    List.of(Message.user("watch", Media.of(new byte[1], "video/mp4"))), List.of()));
            var audio = assertThrows(UnsupportedMediaException.class, () -> gpt.chat(
                    List.of(Message.user("listen", Media.of(new byte[1], "audio/wav"))), List.of()));
            var ogg = assertThrows(UnsupportedMediaException.class, () ->
                    client(server, OpenAICompatibleProvider.OPENAI, "gpt-audio-1.5").chat(
                            List.of(Message.user("listen", Media.of(new byte[1], "audio/ogg"))), List.of()));

            assertTrue(video.getMessage().contains("does not accept video input"), video.getMessage());
            assertTrue(audio.getMessage().contains("does not accept audio input"), audio.getMessage());
            assertTrue(ogg.getMessage().contains("use WAV or MP3"), ogg.getMessage());
            assertTrue(server.requests.isEmpty());
        }
    }

    @Test
    void openRouterSendsVideoAudioAndDocumentUrlsAndCustomHeaders() throws Exception {
        try (var server = new LocalServer(r -> LocalServer.Reply.json(DONE))) {
            var provider = OpenAICompatibleProvider.OPENROUTER.withHeaders(Map.of("HTTP-Referer", "https://myapp.example"));
            Message message = Message.user("",
                    Media.fromUrl("https://example.com/clip.mp4"),
                    Media.of(new byte[]{9}, "audio/ogg"),
                    Media.fromUrl("https://example.com/paper.pdf"));

            client(server, provider, "google/gemini-3.8-flash").chat(List.of(message), List.of());

            JSONArray parts = userParts(server);
            assertEquals(3, parts.length(), "no empty text part");
            assertEquals("https://example.com/clip.mp4", parts.getJSONObject(0).getJSONObject("video_url").getString("url"));
            assertEquals("ogg", parts.getJSONObject(1).getJSONObject("input_audio").getString("format"));
            assertEquals("https://example.com/paper.pdf", parts.getJSONObject(2).getJSONObject("file").getString("file_data"));
            assertEquals("https://myapp.example", server.requests.get(0).header("Http-referer"));
        }
    }

    @Test
    void textDocumentsBecomeTextEverywhere() throws Exception {
        try (var server = new LocalServer(r -> LocalServer.Reply.json(DONE))) {
            Message message = Message.user("Summarize", Media.of("line one".getBytes(), "text/plain").withName("notes.txt"));

            client(server, OpenAICompatibleProvider.OPENAI, "gpt-5").chat(List.of(message), List.of());

            assertEquals("[notes.txt]\nline one", userParts(server).getJSONObject(1).getString("text"));
        }
    }

    @Test
    void toolCallsAndResultsRoundTrip() throws Exception {
        try (var server = new LocalServer(r -> LocalServer.Reply.json(DONE))) {
            List<Message> history = List.of(
                    new Message(Role.USER, "add milk", null),
                    new Message(Role.ASSISTANT, "", null, null, List.of(new ToolCall("c1", "add_todo", "{\"task\":\"milk\"}"))),
                    new Message(Role.TOOL, "Added", "c1", "add_todo", null));

            client(server, OpenAICompatibleProvider.OPENAI, "gpt-5").chat(history, List.of(
                    new ToolSchema("add_todo", "Adds", "{\"type\":\"object\",\"properties\":{}}")));

            JSONArray messages = new JSONObject(server.requests.get(0).text()).getJSONArray("messages");
            JSONObject assistant = messages.getJSONObject(1);
            assertTrue(assistant.isNull("content"));
            assertEquals("add_todo", assistant.getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").getString("name"));
            JSONObject tool = messages.getJSONObject(2);
            assertEquals("c1", tool.getString("tool_call_id"));
            assertFalse(tool.has("name"), "OpenAI tool results carry no name");
        }
    }

    @Test
    void streamingCollectsTextAndToolCallsAndSkipsKeepAliveComments() throws Exception {
        try (var server = new LocalServer(r -> LocalServer.Reply.sse(
                ": OPENROUTER PROCESSING",
                "{\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}",
                "{\"choices\":[{\"delta\":{\"content\":\"lo\"}}]}",
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"function\":{\"name\":\"echo\",\"arguments\":\"{\\\"te\"}}]}}]}",
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"xt\\\":\\\"hi\\\"}\"}}]}}]}",
                "{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}",
                "{\"choices\":[],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":4,\"total_tokens\":7}}",
                "[DONE]"))) {
            List<String> deltas = new ArrayList<>();

            ChatResponse response = client(server, OpenAICompatibleProvider.OPENROUTER, "x/y")
                    .streamChat(List.of(new Message(Role.USER, "hi", null)), List.of(), deltas::add);

            assertEquals(List.of("Hel", "lo"), deltas);
            assertEquals("Hello", response.getAssistantMessage().getContent());
            assertEquals("{\"text\":\"hi\"}", response.getToolCalls().get(0).getArgumentsJson());
            assertEquals(FinishReason.TOOL_CALLS, response.getFinishReason());
            assertEquals(new ModelUsage(3, 4, 7), response.getUsage());
            assertTrue(new JSONObject(server.requests.get(0).text()).getJSONObject("stream_options").getBoolean("include_usage"));
        }
    }

    @Test
    void errorsInsideAStreamAreRaised() throws Exception {
        try (var server = new LocalServer(r -> LocalServer.Reply.sse(
                "{\"choices\":[{\"delta\":{\"content\":\"par\"}}]}",
                "{\"error\":{\"code\":502,\"message\":\"upstream provider failed\"}}"))) {
            RuntimeException e = assertThrows(RuntimeException.class, () ->
                    client(server, OpenAICompatibleProvider.OPENROUTER, "x/y")
                            .streamChat(List.of(new Message(Role.USER, "hi", null)), List.of(), null));

            assertTrue(e.getMessage().contains("upstream provider failed"), e.getMessage());
        }
    }

    @Test
    void providersWithoutAKeySendNoAuthorizationHeader() throws Exception {
        try (var server = new LocalServer(r -> LocalServer.Reply.json(DONE))) {
            var local = OpenAICompatibleProvider.custom("Local", server.url() + "/v1");

            new OpenAIModelClient(local, null, "llama", FAST).chat(List.of(new Message(Role.USER, "hi", null)), List.of());

            assertNull(server.requests.get(0).header("Authorization"));
        }
    }

    @Test
    void keyIsRequiredWhereTheProviderNeedsOne() {
        assertThrows(IllegalArgumentException.class,
                () -> new OpenAIModelClient(OpenAICompatibleProvider.OPENAI, "", "gpt-5", FAST));
    }
}
