package ai.lambda.ai.client;

import ai.lambda.ai.core.Media;
import ai.lambda.ai.generation.ImageRequest;
import ai.lambda.ai.generation.SpeechRequest;
import ai.lambda.ai.generation.VideoRequest;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MediaGenerationTest {

    private static final HttpOptions FAST = new HttpOptions(Duration.ofSeconds(2), Duration.ofSeconds(5), 1, Duration.ofMillis(1));
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G'};
    private static final String PNG_B64 = Base64.getEncoder().encodeToString(PNG);

    private static OpenAICompatibleProvider local(LocalServer server, OpenAICompatibleProvider provider) {
        return provider.withBaseUrl(server.url() + "/v1");
    }

    // --- OpenAI protocol ---

    @Test
    void openAiImagesDecodeBase64() throws Exception {
        try (var server = new LocalServer(r -> LocalServer.Reply.json(
                "{\"data\":[{\"b64_json\":\"" + PNG_B64 + "\"}],\"output_format\":\"png\"}"))) {
            var generator = new OpenAIImageGenerator(local(server, OpenAICompatibleProvider.OPENAI), "k", "gpt-image-2", FAST);

            List<Media> images = generator.generateImages(ImageRequest.of("a fox").withSize("1024x1024")
                    .withOptions(java.util.Map.of("quality", "high")));

            assertEquals("image/png", images.get(0).mimeType());
            assertArrayEquals(PNG, images.get(0).data());
            JSONObject body = new JSONObject(server.requests.get(0).text());
            assertEquals("gpt-image-2", body.getString("model"));
            assertEquals("1024x1024", body.getString("size"));
            assertEquals("high", body.getString("quality"));
            assertEquals("/v1/images/generations", server.requests.get(0).path());
        }
    }

    @Test
    void openAiImagesGivenAsUrlsAreDownloaded() throws Exception {
        try (var server = new LocalServer(r -> r.path().equals("/img.png")
                ? LocalServer.Reply.bytes("image/png", PNG)
                : LocalServer.Reply.json("{\"data\":[{\"url\":\"" + "URL" + "\"}]}"))) {
            // The generation reply points back at this server for the download.
            var serverWithUrl = new LocalServer(r -> r.path().equals("/img.png")
                    ? LocalServer.Reply.bytes("image/png", PNG)
                    : LocalServer.Reply.json("{\"data\":[{\"url\":\"" + server.url() + "/img.png\"}]}"));
            try (serverWithUrl) {
                var generator = new OpenAIImageGenerator(local(serverWithUrl, OpenAICompatibleProvider.OPENAI), "k", "dall-e-3", FAST);
                assertArrayEquals(PNG, generator.generateImage("a fox").data());
            }
        }
    }

    @Test
    void openAiSpeechReturnsTheAudioBytes() throws Exception {
        try (var server = new LocalServer(r -> LocalServer.Reply.bytes("audio/mpeg", new byte[]{1, 2, 3}))) {
            var tts = new OpenAISpeechGenerator(local(server, OpenAICompatibleProvider.OPENAI), "k", "gpt-4o-mini-tts", FAST);

            Media audio = tts.generateSpeech(SpeechRequest.of("hello").withVoice("coral").withInstructions("cheerful"));

            assertEquals("audio/mpeg", audio.mimeType());
            assertArrayEquals(new byte[]{1, 2, 3}, audio.data());
            JSONObject body = new JSONObject(server.requests.get(0).text());
            assertEquals("coral", body.getString("voice"));
            assertEquals("cheerful", body.getString("instructions"));
            assertEquals("mp3", body.getString("response_format"));
        }
    }

    @Test
    void transcriptionSendsAMultipartUpload() throws Exception {
        try (var server = new LocalServer(r -> LocalServer.Reply.json("{\"text\":\"hello world\"}"))) {
            var stt = new OpenAITranscriber(local(server, OpenAICompatibleProvider.OPENAI), "k", "gpt-transcribe", FAST);

            String text = stt.transcribe(Media.of("RIFFDATA".getBytes(StandardCharsets.UTF_8), "audio/wav").withName("note.wav"), "en");

            assertEquals("hello world", text);
            LocalServer.Request request = server.requests.get(0);
            assertTrue(request.header("Content-type").startsWith("multipart/form-data; boundary="));
            String body = request.text();
            assertTrue(body.contains("name=\"model\"\r\n\r\ngpt-transcribe\r\n"), body);
            assertTrue(body.contains("name=\"language\"\r\n\r\nen\r\n"), body);
            assertTrue(body.contains("name=\"file\"; filename=\"note.wav\"\r\nContent-Type: audio/wav\r\n\r\nRIFFDATA\r\n"), body);
        }
    }

    @Test
    void openRouterImagesComeBackAsDataUrls() throws Exception {
        try (var server = new LocalServer(r -> LocalServer.Reply.json(
                "{\"choices\":[{\"message\":{\"content\":\"here\",\"images\":[{\"image_url\":{\"url\":\"data:image/png;base64,"
                        + PNG_B64 + "\"}}]}}]}"))) {
            var generator = new OpenRouterImageGenerator(local(server, OpenAICompatibleProvider.OPENROUTER), "k",
                    "google/gemini-3.1-flash-image", FAST);

            Media image = generator.generateImages(ImageRequest.of("a fox").withSize("16:9")).get(0);

            assertArrayEquals(PNG, image.data());
            JSONObject body = new JSONObject(server.requests.get(0).text());
            assertEquals(List.of("image", "text"), body.getJSONArray("modalities").toList());
            assertEquals("16:9", body.getJSONObject("image_config").getString("aspect_ratio"));
            assertFalse(body.getBoolean("stream"));
        }
    }

    // --- Gemini ---

    private static String inlineReply(String mimeType, byte[] data) {
        return "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"done\"},{\"inlineData\":{\"mimeType\":\"" + mimeType
                + "\",\"data\":\"" + Base64.getEncoder().encodeToString(data) + "\"}}]}}]}";
    }

    @Test
    void geminiImagesSendPromptReferencesAndAspectRatio() throws Exception {
        try (var server = new LocalServer(r -> LocalServer.Reply.json(inlineReply("image/jpeg", PNG)))) {
            var images = new GeminiMedia.Images(GeminiMedia.api("k", "gemini-3.1-flash-image", FAST, server.url()));

            Media image = images.generateImages(ImageRequest.of("make it blue")
                    .withReferenceImages(Media.of(new byte[]{7}, "image/png")).withSize("16:9")).get(0);

            assertEquals("image/jpeg", image.mimeType());
            LocalServer.Request request = server.requests.get(0);
            assertEquals("/v1beta/models/gemini-3.1-flash-image:generateContent", request.path());
            assertEquals("k", request.header("X-goog-api-key"));
            JSONObject body = new JSONObject(request.text());
            JSONObject config = body.getJSONObject("generationConfig");
            assertEquals(List.of("IMAGE"), config.getJSONArray("responseModalities").toList());
            assertEquals("16:9", config.getJSONObject("imageConfig").getString("aspectRatio"));
            assertEquals("image/png", body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
                    .getJSONObject(1).getJSONObject("inlineData").getString("mimeType"));
        }
    }

    @Test
    void geminiSpeechWrapsRawPcmInAWavHeader() throws Exception {
        byte[] pcm = new byte[480];
        try (var server = new LocalServer(r -> LocalServer.Reply.json(inlineReply("audio/L16;codec=pcm;rate=24000", pcm)))) {
            var tts = new GeminiMedia.Speech(GeminiMedia.api("k", "gemini-3.1-flash-tts-preview", FAST, server.url()));

            Media audio = tts.generateSpeech(SpeechRequest.of("Hello").withVoice("Puck"));

            assertEquals("audio/wav", audio.mimeType());
            byte[] wav = audio.data();
            assertEquals(44 + pcm.length, wav.length);
            assertEquals("RIFF", new String(wav, 0, 4, StandardCharsets.US_ASCII));
            assertEquals(24000, java.nio.ByteBuffer.wrap(wav, 24, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt());
            JSONObject config = new JSONObject(server.requests.get(0).text()).getJSONObject("generationConfig");
            assertEquals("Puck", config.getJSONObject("speechConfig").getJSONObject("voiceConfig")
                    .getJSONObject("prebuiltVoiceConfig").getString("voiceName"));
        }
    }

    @Test
    void geminiSpeechThatIsAlreadyWavIsKeptAsIs() throws Exception {
        byte[] wav = GeminiMedia.wav(new byte[10], 24000, 1, 16);
        try (var server = new LocalServer(r -> LocalServer.Reply.json(inlineReply("audio/wav", wav)))) {
            var tts = new GeminiMedia.Speech(GeminiMedia.api("k", "gemini-3.8-flash-tts", FAST, server.url()));

            assertArrayEquals(wav, tts.generateSpeech("Hi").data());
        }
    }

    @Test
    void veoVideosArePolledThenDownloaded() throws Exception {
        AtomicInteger polls = new AtomicInteger();
        byte[] mp4 = {0, 0, 0, 24, 'f', 't', 'y', 'p'};
        var holder = new LocalServer[1];
        try (var server = new LocalServer(r -> {
            if (r.path().endsWith(":predictLongRunning")) return LocalServer.Reply.json("{\"name\":\"models/veo/operations/op1\"}");
            if (r.path().equals("/v1beta/models/veo/operations/op1")) {
                return polls.incrementAndGet() < 2
                        ? LocalServer.Reply.json("{\"name\":\"models/veo/operations/op1\",\"done\":false}")
                        : LocalServer.Reply.json("{\"done\":true,\"response\":{\"generateVideoResponse\":{\"generatedSamples\":"
                                + "[{\"video\":{\"uri\":\"" + holder[0].url() + "/files/v.mp4\"}}]}}}");
            }
            if (r.path().equals("/files/v.mp4")) return LocalServer.Reply.bytes("video/mp4", mp4);
            return new LocalServer.Reply(404, "text/plain", new byte[0]);
        })) {
            holder[0] = server;
            var veo = new GeminiMedia.Videos(GeminiMedia.api("k", "veo-3.1-generate-preview", FAST, server.url()));

            Media video = veo.generateVideo(VideoRequest.of("waves").withSeconds(8).withSize("16:9")
                    .withPollInterval(Duration.ofMillis(10)));

            assertArrayEquals(mp4, video.data());
            assertEquals("video/mp4", video.mimeType());
            JSONObject body = new JSONObject(server.requests.get(0).text());
            assertEquals("waves", body.getJSONArray("instances").getJSONObject(0).getString("prompt"));
            assertEquals("8", body.getJSONObject("parameters").getString("durationSeconds"));
            assertEquals("16:9", body.getJSONObject("parameters").getString("aspectRatio"));
            assertEquals(2, polls.get());
            assertEquals("k", server.requests.get(server.requests.size() - 1).header("X-goog-api-key"),
                    "the download carries the API key");
        }
    }

    @Test
    void veoGivesUpAfterTheTimeout() throws Exception {
        try (var server = new LocalServer(r -> LocalServer.Reply.json("{\"name\":\"operations/slow\",\"done\":false}"))) {
            var veo = new GeminiMedia.Videos(GeminiMedia.api("k", "veo", FAST, server.url()));

            RuntimeException e = assertThrows(RuntimeException.class, () -> veo.generateVideo(VideoRequest.of("x")
                    .withTimeout(Duration.ofMillis(50)).withPollInterval(Duration.ofMillis(10))));

            assertTrue(e.getMessage().contains("not ready"), e.getMessage());
        }
    }
}
