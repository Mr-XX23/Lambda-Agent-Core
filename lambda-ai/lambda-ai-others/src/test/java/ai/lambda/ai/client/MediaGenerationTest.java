package ai.lambda.ai.client;

import ai.lambda.ai.core.HttpOptions;
import ai.lambda.ai.core.Media;
import ai.lambda.ai.generation.ImageRequest;
import ai.lambda.ai.generation.SpeechRequest;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

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
            var generator = new OpenAICompatibleImageGenerator(local(server, OpenAICompatibleProvider.OPENAI), "k", "gpt-image-2", FAST);

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
                var generator = new OpenAICompatibleImageGenerator(local(serverWithUrl, OpenAICompatibleProvider.OPENAI), "k", "dall-e-3", FAST);
                assertArrayEquals(PNG, generator.generateImage("a fox").data());
            }
        }
    }

    @Test
    void openAiSpeechReturnsTheAudioBytes() throws Exception {
        try (var server = new LocalServer(r -> LocalServer.Reply.bytes("audio/mpeg", new byte[]{1, 2, 3}))) {
            var tts = new OpenAICompatibleSpeechGenerator(local(server, OpenAICompatibleProvider.OPENAI), "k", "gpt-4o-mini-tts", FAST);

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
            var stt = new OpenAICompatibleTranscriber(local(server, OpenAICompatibleProvider.OPENAI), "k", "gpt-transcribe", FAST);

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
}
