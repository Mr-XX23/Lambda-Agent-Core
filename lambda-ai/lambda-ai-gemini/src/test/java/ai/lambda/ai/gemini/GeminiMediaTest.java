package ai.lambda.ai.gemini;

import ai.lambda.ai.core.HttpOptions;
import ai.lambda.ai.core.Media;
import ai.lambda.ai.generation.ImageRequest;
import ai.lambda.ai.generation.SpeechRequest;
import ai.lambda.ai.generation.VideoRequest;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Runs image, speech and video generation through the official SDK against a local server. */
class GeminiMediaTest {

    private static final HttpOptions FAST = new HttpOptions(Duration.ofSeconds(2), Duration.ofSeconds(5), 1, Duration.ofMillis(1));
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G'};

    private static String inlineReply(String mimeType, byte[] data) {
        return "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"done\"},{\"inlineData\":{\"mimeType\":\"" + mimeType
                + "\",\"data\":\"" + Base64.getEncoder().encodeToString(data) + "\"}}]},\"finishReason\":\"STOP\"}]}";
    }

    private static com.google.genai.Client client(LocalServer server) {
        return GeminiMedia.client("k", FAST, server.url());
    }

    private static JSONObject body(LocalServer server, int index) {
        return new JSONObject(server.requests.get(index).text());
    }

    @Test
    void imagesSendThePromptReferencesAndAspectRatio() throws Exception {
        try (var server = new LocalServer(r -> LocalServer.Reply.json(inlineReply("image/jpeg", PNG)))) {
            var images = GeminiMedia.images(client(server), "gemini-3.1-flash-image");

            Media image = images.generateImages(ImageRequest.of("make it blue")
                    .withReferenceImages(Media.of(new byte[]{7}, "image/png")).withSize("16:9")).get(0);

            assertEquals("image/jpeg", image.mimeType());
            assertArrayEquals(PNG, image.data());
            LocalServer.Request request = server.requests.get(0);
            assertEquals("/v1beta/models/gemini-3.1-flash-image:generateContent", request.path());
            assertEquals("k", request.header("X-goog-api-key"));
            JSONObject config = body(server, 0).getJSONObject("generationConfig");
            assertEquals(List.of("IMAGE"), config.getJSONArray("responseModalities").toList());
            assertEquals("16:9", config.getJSONObject("imageConfig").getString("aspectRatio"));
            JSONObject reference = body(server, 0).getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
                    .getJSONObject(1).getJSONObject("inlineData");
            assertEquals("image/png", reference.getString("mimeType"));
            assertEquals("Bw==", reference.getString("data"));
        }
    }

    @Test
    void imageSizesAndCountsAreApplied() throws Exception {
        try (var server = new LocalServer(r -> LocalServer.Reply.json(inlineReply("image/png", PNG)))) {
            var images = GeminiMedia.images(client(server), "gemini-3.1-flash-image");

            assertEquals(2, images.generateImages(ImageRequest.of("a fox").withSize("2k").withCount(2)).size());
            assertEquals("2K", body(server, 0).getJSONObject("generationConfig").getJSONObject("imageConfig").getString("imageSize"));
            assertThrows(IllegalArgumentException.class, () -> images.generateImages(ImageRequest.of("x").withSize("huge")));
            assertThrows(IllegalArgumentException.class, () -> images.generateImages(ImageRequest.of("x")
                    .withReferenceImages(Media.fromUrl("https://example.com/a.png"))));
        }
    }

    @Test
    void optionsAreSentAsExtraRequestFields() throws Exception {
        try (var server = new LocalServer(r -> LocalServer.Reply.json(inlineReply("image/png", PNG)))) {
            GeminiMedia.images(client(server), "gemini-3.1-flash-image")
                    .generateImages(ImageRequest.of("a fox").withOptions(Map.of("labels", Map.of("team", "design"))));

            assertEquals("design", body(server, 0).getJSONObject("labels").getString("team"));
            assertEquals(List.of("IMAGE"), body(server, 0).getJSONObject("generationConfig").getJSONArray("responseModalities").toList(),
                    "the extra fields are added, not replacing what the SDK sends");
        }
    }

    @Test
    void speechWrapsRawPcmInAWavHeader() throws Exception {
        byte[] pcm = new byte[480];
        try (var server = new LocalServer(r -> LocalServer.Reply.json(inlineReply("audio/L16;codec=pcm;rate=24000", pcm)))) {
            var tts = GeminiMedia.speech(client(server), "gemini-3.1-flash-tts-preview");

            Media audio = tts.generateSpeech(SpeechRequest.of("Hello").withVoice("Puck").withInstructions("Say cheerfully"));

            assertEquals("audio/wav", audio.mimeType());
            byte[] wav = audio.data();
            assertEquals(44 + pcm.length, wav.length);
            assertEquals("RIFF", new String(wav, 0, 4, StandardCharsets.US_ASCII));
            assertEquals(24000, ByteBuffer.wrap(wav, 24, 4).order(ByteOrder.LITTLE_ENDIAN).getInt());
            JSONObject request = body(server, 0);
            JSONObject config = request.getJSONObject("generationConfig");
            assertEquals(List.of("AUDIO"), config.getJSONArray("responseModalities").toList());
            assertEquals("Puck", config.getJSONObject("speechConfig").getJSONObject("voiceConfig")
                    .getJSONObject("prebuiltVoiceConfig").getString("voiceName"));
            assertEquals("Say cheerfully: Hello", request.getJSONArray("contents").getJSONObject(0)
                    .getJSONArray("parts").getJSONObject(0).getString("text"));
        }
    }

    @Test
    void speechThatIsAlreadyWavIsKeptAsIsAndOtherFormatsAreRefused() throws Exception {
        byte[] wav = GeminiMedia.wav(new byte[10], 24000, 1, 16);
        try (var server = new LocalServer(r -> LocalServer.Reply.json(inlineReply("audio/wav", wav)))) {
            var tts = GeminiMedia.speech(client(server), "gemini-3.8-flash-tts");

            assertArrayEquals(wav, tts.generateSpeech("Hi").data());
            assertEquals("Kore", body(server, 0).getJSONObject("generationConfig").getJSONObject("speechConfig")
                    .getJSONObject("voiceConfig").getJSONObject("prebuiltVoiceConfig").getString("voiceName"), "the default voice");
            assertThrows(IllegalArgumentException.class, () -> tts.generateSpeech(SpeechRequest.of("Hi").withFormat("mp3")));
        }
    }

    @Test
    void anAnswerWithoutMediaIsReported() throws Exception {
        try (var server = new LocalServer(r -> LocalServer.Reply.json(
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"I can't draw that\"}]},\"finishReason\":\"SAFETY\"}]}"))) {
            RuntimeException e = assertThrows(RuntimeException.class,
                    () -> GeminiMedia.images(client(server), "gemini-3.1-flash-image").generateImage("something"));
            assertTrue(e.getMessage().contains("no media") && e.getMessage().contains("SAFETY"), e.getMessage());
        }
    }

    @Test
    void veoVideosArePolledThenDownloaded() throws Exception {
        AtomicInteger polls = new AtomicInteger();
        byte[] mp4 = {0, 0, 0, 24, 'f', 't', 'y', 'p'};
        var holder = new LocalServer[1];
        try (var server = new LocalServer(r -> {
            if (r.path().endsWith(":predictLongRunning")) return LocalServer.Reply.json("{\"name\":\"models/veo/operations/op1\"}");
            if (r.path().endsWith("/operations/op1")) {
                return polls.incrementAndGet() < 2
                        ? LocalServer.Reply.json("{\"name\":\"models/veo/operations/op1\",\"done\":false}")
                        : LocalServer.Reply.json("{\"name\":\"models/veo/operations/op1\",\"done\":true,\"response\":{\"generateVideoResponse\":"
                                + "{\"generatedSamples\":[{\"video\":{\"uri\":\"" + holder[0].url() + "/v1beta/files/v1:download?alt=media\"}}]}}}");
            }
            if (r.path().contains("/files/v1")) return LocalServer.Reply.bytes("video/mp4", mp4);
            return new LocalServer.Reply(404, "text/plain", new byte[0]);
        })) {
            holder[0] = server;
            var veo = GeminiMedia.videos(client(server), "veo-3.1-generate-preview");

            Media video = veo.generateVideo(VideoRequest.of("waves").withSeconds(8).withSize("16:9")
                    .withPollInterval(Duration.ofMillis(10)));

            assertArrayEquals(mp4, video.data());
            assertEquals("video/mp4", video.mimeType());
            JSONObject request = body(server, 0);
            assertEquals("waves", request.getJSONArray("instances").getJSONObject(0).getString("prompt"));
            assertEquals(8, request.getJSONObject("parameters").getInt("durationSeconds"));
            assertEquals("16:9", request.getJSONObject("parameters").getString("aspectRatio"));
            assertEquals(2, polls.get());
            assertEquals("k", server.requests.getLast().header("X-goog-api-key"), "the download carries the API key");
        }
    }

    @Test
    void veoGivesUpAfterTheTimeoutAndReportsFailures() throws Exception {
        try (var slow = new LocalServer(r -> LocalServer.Reply.json("{\"name\":\"models/veo/operations/slow\",\"done\":false}"));
             var failing = new LocalServer(r -> LocalServer.Reply.json(
                     "{\"name\":\"models/veo/operations/bad\",\"done\":true,\"error\":{\"code\":3,\"message\":\"prompt was blocked\"}}"))) {
            RuntimeException late = assertThrows(RuntimeException.class, () -> GeminiMedia.videos(client(slow), "veo")
                    .generateVideo(VideoRequest.of("x").withTimeout(Duration.ofMillis(50)).withPollInterval(Duration.ofMillis(10))));
            assertTrue(late.getMessage().contains("not ready"), late.getMessage());

            RuntimeException failed = assertThrows(RuntimeException.class,
                    () -> GeminiMedia.videos(client(failing), "veo").generateVideo("x"));
            assertTrue(failed.getMessage().contains("prompt was blocked"), failed.getMessage());
        }
    }
}
