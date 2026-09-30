package ai.lambda.ai.client;

import ai.lambda.ai.core.*;
import ai.lambda.ai.generation.ImageRequest;
import ai.lambda.ai.generation.SpeechRequest;
import ai.lambda.ai.generation.VideoRequest;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Provider-specific formats: Mistral, xAI, Ollama and Perplexity. */
class ProviderPresetsTest {

    private static final HttpOptions FAST = new HttpOptions(Duration.ofSeconds(2), Duration.ofSeconds(5), 1, Duration.ofMillis(1));
    private static final String DONE = "{\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}";

    private static JSONObject body(LocalServer server) {
        return new JSONObject(server.requests.get(0).text());
    }

    // --- Mistral ---

    @Test
    void mistralUsesPlainStringsForMediaAndNamesToolResults() throws Exception {
        try (var server = new LocalServer(r -> LocalServer.Reply.json(DONE))) {
            var mistral = OpenAICompatibleProvider.MISTRAL.withBaseUrl(server.url() + "/v1");
            List<Message> history = List.of(
                    Message.user("read", Media.fromUrl("https://example.com/a.png"), Media.fromUrl("https://arxiv.org/pdf/1.pdf")),
                    new Message(Role.ASSISTANT, "", null, null, List.of(new ToolCall("D681PevKs", "lookup", "{}"))),
                    new Message(Role.TOOL, "found", "D681PevKs", "lookup", null));

            new OpenAICompatibleModelClient(mistral, "k", "mistral-medium-latest", FAST).chat(history, List.of());

            JSONArray messages = body(server).getJSONArray("messages");
            JSONArray parts = messages.getJSONObject(0).getJSONArray("content");
            assertEquals("https://example.com/a.png", parts.getJSONObject(1).getString("image_url"));
            assertEquals("document_url", parts.getJSONObject(2).getString("type"));
            assertEquals("https://arxiv.org/pdf/1.pdf", parts.getJSONObject(2).getString("document_url"));
            assertEquals("lookup", messages.getJSONObject(2).getString("name"));
        }
    }

    @Test
    void mistralVoxtralTakesAudioAsAString() throws Exception {
        try (var server = new LocalServer(r -> LocalServer.Reply.json(DONE))) {
            var mistral = OpenAICompatibleProvider.MISTRAL.withBaseUrl(server.url() + "/v1");

            new OpenAICompatibleModelClient(mistral, "k", "voxtral-small-latest", FAST)
                    .chat(List.of(Message.user("what is said?", Media.of(new byte[]{1, 2}, "audio/mpeg"))), List.of());

            assertEquals("AQI=", body(server).getJSONArray("messages").getJSONObject(0).getJSONArray("content")
                    .getJSONObject(1).getString("input_audio"));
            assertThrows(UnsupportedMediaException.class, () -> new OpenAICompatibleModelClient(mistral, "k", "mistral-medium-latest", FAST)
                    .chat(List.of(Message.user("x", Media.of(new byte[1], "audio/mpeg"))), List.of()));
        }
    }

    @Test
    void mistralSpeechDecodesTheJsonWrappedAudio() throws Exception {
        try (var server = new LocalServer(r -> LocalServer.Reply.json("{\"audio_data\":\"AQID\"}"))) {
            var tts = new MistralMedia.Speech(new JsonHttp("Mistral", FAST, JsonHttp.bearer("k")), server.url() + "/v1",
                    "voxtral-mini-tts-2603");

            Media audio = tts.generateSpeech(SpeechRequest.of("Bonjour").withVoice("v1").withFormat("wav"));

            assertEquals("audio/wav", audio.mimeType());
            assertArrayEquals(new byte[]{1, 2, 3}, audio.data());
            assertEquals("v1", body(server).getString("voice_id"));
            assertEquals("/v1/audio/speech", server.requests.get(0).path());
        }
    }

    // --- xAI ---

    @Test
    void xaiImagesTakeAspectRatioAndReturnMimeTypes() throws Exception {
        try (var server = new LocalServer(r -> LocalServer.Reply.json(
                "{\"data\":[{\"b64_json\":\"AQID\",\"mime_type\":\"image/jpeg\"}]}"))) {
            var xai = OpenAICompatibleProvider.XAI.withBaseUrl(server.url() + "/v1");

            Media image = new OpenAIImageGenerator(xai, "k", "grok-imagine-image-2.0", FAST)
                    .generateImages(ImageRequest.of("a fox").withSize("16:9")).get(0);

            assertEquals("image/jpeg", image.mimeType());
            JSONObject body = body(server);
            assertEquals("16:9", body.getString("aspect_ratio"));
            assertEquals("b64_json", body.getString("response_format"));
            assertFalse(body.has("size"));
        }
    }

    @Test
    void xaiSpeechAndTranscription() throws Exception {
        try (var server = new LocalServer(r -> r.path().endsWith("/tts")
                ? LocalServer.Reply.bytes("audio/mpeg", new byte[]{5})
                : LocalServer.Reply.json("{\"text\":\"hi there\",\"language\":\"en\"}"))) {
            JsonHttp http = XaiMedia.http("k", FAST);

            Media audio = new XaiMedia.Speech(http, server.url() + "/v1").generateSpeech("hello");
            String text = new XaiMedia.Stt(http, server.url() + "/v1", "grok-voice-transcribe-2.0")
                    .transcribe(Media.of(new byte[]{1}, "audio/wav"), "en");

            assertEquals("audio/mpeg", audio.mimeType());
            JSONObject tts = new JSONObject(server.requests.get(0).text());
            assertEquals("eve", tts.getString("voice_id"));
            assertEquals("mp3", tts.getJSONObject("output_format").getString("codec"));
            assertEquals("hi there", text);
            String multipart = server.requests.get(1).text();
            assertTrue(multipart.indexOf("name=\"model\"") < multipart.indexOf("name=\"file\""), "file must be the last field");
        }
    }

    @Test
    void xaiVideosArePolledThenDownloaded() throws Exception {
        AtomicInteger polls = new AtomicInteger();
        var holder = new LocalServer[1];
        try (var server = new LocalServer(r -> {
            if (r.path().endsWith("/videos/generations")) return LocalServer.Reply.json("{\"request_id\":\"r1\"}");
            if (r.path().endsWith("/videos/r1")) {
                return polls.incrementAndGet() < 2 ? LocalServer.Reply.json("{\"status\":\"pending\"}")
                        : LocalServer.Reply.json("{\"status\":\"done\",\"video\":{\"url\":\"" + holder[0].url() + "/v.mp4\"}}");
            }
            return LocalServer.Reply.bytes("video/mp4", new byte[]{9, 9});
        })) {
            holder[0] = server;
            var videos = new XaiMedia.Videos(XaiMedia.http("k", FAST), server.url() + "/v1", "grok-imagine-video-1.5");

            Media video = videos.generateVideo(VideoRequest.of("a boat").withSeconds(6).withSize("720p")
                    .withPollInterval(Duration.ofMillis(10)));

            assertArrayEquals(new byte[]{9, 9}, video.data());
            JSONObject body = body(server);
            assertEquals(6, body.getInt("duration"));
            assertEquals("720p", body.getString("resolution"));
        }
    }

    @Test
    void xaiChatTakesImagesButNotPdfs() {
        ModelCapabilities caps = OpenAICompatibleModelClient.xai("k", "grok-4.7").capabilities();
        assertTrue(caps.accepts(Modality.IMAGE));
        assertFalse(caps.accepts(Modality.DOCUMENT));
    }

    // --- Ollama ---

    @Test
    void ollamaNeedsNoKeyAndRejectsImageUrls() {
        OpenAICompatibleModelClient local = OpenAICompatibleModelClient.ollama("gemma4");

        UnsupportedMediaException e = assertThrows(UnsupportedMediaException.class, () ->
                local.chat(List.of(Message.user("x", Media.fromUrl("https://example.com/a.png"))), List.of()));

        assertTrue(e.getMessage().contains("cannot fetch image from a URL"), e.getMessage());
        assertEquals("http://localhost:11434/v1", local.provider().baseUrl());
    }

    // --- Perplexity (Responses protocol) ---

    private static ResponsesModelClient perplexity(LocalServer server) {
        return new ResponsesModelClient("Perplexity", server.url() + "/v1", "k", "perplexity/sonar",
                ModelCapabilities.of(Modality.IMAGE).withMediaUrls(Modality.IMAGE), 1000, FAST);
    }

    @Test
    void perplexitySendsInstructionsItemsAndSignatures() throws Exception {
        try (var server = new LocalServer(r -> LocalServer.Reply.json("""
                {"status":"completed","output":[
                  {"type":"function_call","call_id":"call_2","name":"search","arguments":"{\\"q\\":\\"java\\"}","thought_signature":"sig-9"}],
                 "usage":{"input_tokens":5,"output_tokens":2,"total_tokens":7}}
                """))) {
            List<Message> history = List.of(
                    new Message(Role.SYSTEM, "Be brief.", null),
                    Message.user("what is this?", Media.fromUrl("https://example.com/a.png")),
                    new Message(Role.ASSISTANT, "", null, null, List.of(new ToolCall("call_1", "search", "{}", "sig-1"))),
                    new Message(Role.TOOL, "results", "call_1", "search", null));

            ChatResponse response = perplexity(server).chat(history,
                    List.of(new ToolSchema("search", "Searches", "{\"type\":\"object\",\"properties\":{}}")));

            JSONObject body = body(server);
            assertEquals("Be brief.", body.getString("instructions"));
            assertEquals(1000, body.getInt("max_output_tokens"));
            JSONArray input = body.getJSONArray("input");
            assertEquals("input_image", input.getJSONObject(0).getJSONArray("content").getJSONObject(1).getString("type"));
            assertEquals("sig-1", input.getJSONObject(1).getString("thought_signature"));
            assertEquals("function_call_output", input.getJSONObject(2).getString("type"));
            assertEquals("search", body.getJSONArray("tools").getJSONObject(0).getString("name"));
            assertEquals("/v1/responses", server.requests.get(0).path());

            ToolCall call = response.getToolCalls().get(0);
            assertEquals("sig-9", call.getSignature());
            assertEquals(FinishReason.TOOL_CALLS, response.getFinishReason());
            assertEquals(new ModelUsage(5, 2, 7), response.getUsage());
        }
    }

    @Test
    void perplexityStreamsTextDeltas() throws Exception {
        try (var server = new LocalServer(r -> LocalServer.Reply.sse(
                "{\"type\":\"response.created\"}",
                "{\"type\":\"response.output_text.delta\",\"delta\":\"Hel\"}",
                "{\"type\":\"response.output_text.delta\",\"delta\":\"lo\"}",
                "{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[{\"type\":\"message\","
                        + "\"content\":[{\"type\":\"output_text\",\"text\":\"Hello\"}]}]}}"))) {
            List<String> deltas = new ArrayList<>();

            ChatResponse response = perplexity(server).streamChat(List.of(new Message(Role.USER, "hi", null)), List.of(), deltas::add);

            assertEquals(List.of("Hel", "lo"), deltas);
            assertEquals("Hello", response.getAssistantMessage().getContent());
            assertEquals(FinishReason.STOP, response.getFinishReason());
        }
    }

    @Test
    void perplexityStreamErrorsAreRaised() throws Exception {
        try (var server = new LocalServer(r -> LocalServer.Reply.sse(
                "{\"type\":\"response.failed\",\"response\":{\"error\":{\"message\":\"model overloaded\"}}}"))) {
            RuntimeException e = assertThrows(RuntimeException.class, () ->
                    perplexity(server).streamChat(List.of(new Message(Role.USER, "hi", null)), List.of(), null));
            assertTrue(e.getMessage().contains("model overloaded"), e.getMessage());
        }
    }

    @Test
    void perplexityRejectsNonImageMedia() {
        var client = ResponsesModelClient.perplexity("k", "perplexity/sonar");
        assertThrows(UnsupportedMediaException.class, () -> client.chat(
                List.of(Message.user("x", Media.of(new byte[1], "application/pdf"))), List.of()));
    }
}
