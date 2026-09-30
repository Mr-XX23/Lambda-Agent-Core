package ai.lambda.ai.openai;

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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the SDK-based image, speech and transcription classes against a local server. */
class OpenAIGeneratorsTest {

    private static final HttpOptions FAST = new HttpOptions(Duration.ofSeconds(2), Duration.ofSeconds(5), 1, Duration.ofMillis(1));
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G'};
    private static final String PNG_B64 = Base64.getEncoder().encodeToString(PNG);

    private static String v1(LocalServer server) {
        return server.url() + "/v1";
    }

    @Test
    void imagesAreGeneratedAndDecoded() throws Exception {
        try (var server = new LocalServer(r -> LocalServer.Reply.json(
                "{\"created\":1,\"data\":[{\"b64_json\":\"" + PNG_B64 + "\"}],\"output_format\":\"png\"}"))) {
            var images = new OpenAIImageGenerator("k", "gpt-image-2", FAST, v1(server));

            List<Media> result = images.generateImages(ImageRequest.of("a fox").withSize("1024x1024").withCount(1)
                    .withOptions(Map.of("quality", "high")));

            assertEquals("image/png", result.get(0).mimeType());
            assertArrayEquals(PNG, result.get(0).data());
            LocalServer.Request request = server.requests.get(0);
            assertEquals("/v1/images/generations", request.path());
            JSONObject body = new JSONObject(request.text());
            assertEquals("gpt-image-2", body.getString("model"));
            assertEquals("a fox", body.getString("prompt"));
            assertEquals("1024x1024", body.getString("size"));
            assertEquals("high", body.getString("quality"));
            assertEquals("Bearer k", request.header("Authorization"));
        }
    }

    @Test
    void imagesGivenAsUrlsAreDownloaded() throws Exception {
        try (var files = new LocalServer(r -> LocalServer.Reply.bytes("image/png", PNG));
             var server = new LocalServer(r -> LocalServer.Reply.json(
                     "{\"created\":1,\"data\":[{\"url\":\"" + files.url() + "/img.png\"}]}"))) {
            Media image = new OpenAIImageGenerator("k", "dall-e-3", FAST, v1(server)).generateImage("a fox");

            assertArrayEquals(PNG, image.data());
            assertEquals("image/png", image.mimeType());
        }
    }

    @Test
    void aReferenceImageIsEditedWithAMultipartUpload() throws Exception {
        try (var server = new LocalServer(r -> LocalServer.Reply.json(
                "{\"created\":1,\"data\":[{\"b64_json\":\"" + PNG_B64 + "\"}],\"output_format\":\"webp\"}"))) {
            var images = new OpenAIImageGenerator("k", "gpt-image-2", FAST, v1(server));

            Media edited = images.generateImages(ImageRequest.of("make the sky purple")
                    .withReferenceImages(Media.of(PNG, "image/png").withName("photo.png"))).get(0);

            assertEquals("image/webp", edited.mimeType());
            LocalServer.Request request = server.requests.get(0);
            assertEquals("/v1/images/edits", request.path());
            assertTrue(request.header("Content-type").startsWith("multipart/form-data"), request.header("Content-type"));
            String body = new String(request.body(), StandardCharsets.ISO_8859_1);
            assertTrue(body.contains("filename=\"photo.png\""), body);
            assertTrue(body.contains("Content-Type: image/png"), body);
            assertTrue(body.contains("make the sky purple"), body);

            assertThrows(UnsupportedOperationException.class, () -> images.generateImages(ImageRequest.of("x")
                    .withReferenceImages(Media.of(PNG, "image/png"), Media.of(PNG, "image/png"))));
        }
    }

    @Test
    void speechReturnsTheAudioBytes() throws Exception {
        try (var server = new LocalServer(r -> LocalServer.Reply.bytes("audio/wav", new byte[]{1, 2, 3}))) {
            var tts = new OpenAISpeechGenerator("k", "gpt-4o-mini-tts", FAST, v1(server));

            Media audio = tts.generateSpeech(SpeechRequest.of("hello").withVoice("coral").withInstructions("cheerful").withFormat("WAV"));

            assertEquals("audio/wav", audio.mimeType());
            assertArrayEquals(new byte[]{1, 2, 3}, audio.data());
            assertEquals("/v1/audio/speech", server.requests.get(0).path());
            JSONObject body = new JSONObject(server.requests.get(0).text());
            assertEquals("hello", body.getString("input"));
            assertEquals("coral", body.getString("voice"));
            assertEquals("cheerful", body.getString("instructions"));
            assertEquals("wav", body.getString("response_format"));

            tts.generateSpeech("default voice");
            JSONObject defaults = new JSONObject(server.requests.get(1).text());
            assertEquals("alloy", defaults.getString("voice"));
            assertEquals("mp3", defaults.getString("response_format"));
            assertThrows(IllegalArgumentException.class, () -> tts.generateSpeech(SpeechRequest.of("x").withFormat("ogg")));
        }
    }

    @Test
    void transcriptionUploadsTheAudio() throws Exception {
        try (var server = new LocalServer(r -> LocalServer.Reply.json("{\"text\":\"hello world\"}"))) {
            var stt = new OpenAITranscriber("k", "gpt-transcribe", FAST, v1(server));

            String text = stt.transcribe(Media.of("RIFFDATA".getBytes(StandardCharsets.UTF_8), "audio/wav").withName("note.wav"), "en");

            assertEquals("hello world", text);
            LocalServer.Request request = server.requests.get(0);
            assertEquals("/v1/audio/transcriptions", request.path());
            assertTrue(request.header("Content-type").startsWith("multipart/form-data"), request.header("Content-type"));
            String body = request.text();
            assertTrue(body.contains("filename=\"note.wav\""), body);
            assertTrue(body.contains("Content-Type: audio/wav"), body);
            assertTrue(body.contains("RIFFDATA"), body);
            assertTrue(body.contains("gpt-transcribe"), body);
            assertTrue(body.contains("name=\"language\""), body);

            assertThrows(IllegalArgumentException.class, () -> stt.transcribe(Media.of(PNG, "image/png")));
            assertThrows(IllegalArgumentException.class, () -> stt.transcribe(Media.fromUrl("https://example.com/a.mp3")));
        }
    }
}
