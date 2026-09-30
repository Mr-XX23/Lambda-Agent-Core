package ai.lambda.ai.core;

import ai.lambda.ai.generation.ImageRequest;
import ai.lambda.ai.generation.SpeechRequest;
import ai.lambda.ai.generation.VideoRequest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The shared request and settings types every provider module builds on. */
class RequestTypesTest {

    private static final Media PNG = Media.of(new byte[]{1}, "image/png");

    @Test
    void imageRequestsHaveDefaultsAndCopies() {
        ImageRequest request = ImageRequest.of("a fox");
        assertEquals(List.of(), request.referenceImages());
        assertNull(request.size());
        assertEquals(1, request.count());
        assertEquals(Map.of(), request.options());

        ImageRequest changed = request.withSize("16:9").withCount(2).withReferenceImages(PNG).withOptions(Map.of("quality", "high"));
        assertEquals("16:9", changed.size());
        assertEquals(2, changed.count());
        assertEquals(List.of(PNG), changed.referenceImages());
        assertEquals("high", changed.options().get("quality"));
        assertEquals("a fox", changed.prompt());

        assertThrows(IllegalArgumentException.class, () -> ImageRequest.of(" "));
        assertThrows(NullPointerException.class, () -> ImageRequest.of(null));
        assertThrows(IllegalArgumentException.class, () -> request.withCount(0));
    }

    @Test
    void speechRequestsHaveDefaultsAndCopies() {
        SpeechRequest request = SpeechRequest.of("Hello");
        assertNull(request.voice());
        assertNull(request.format());

        SpeechRequest changed = request.withVoice("Kore").withInstructions("slowly").withFormat("wav").withOptions(Map.of("speed", 1.2));
        assertEquals(List.of("Kore", "slowly", "wav"), List.of(changed.voice(), changed.instructions(), changed.format()));
        assertEquals(1.2, changed.options().get("speed"));
        assertThrows(IllegalArgumentException.class, () -> SpeechRequest.of(""));
    }

    @Test
    void videoRequestsHaveDefaultsAndCopies() {
        VideoRequest request = VideoRequest.of("waves");
        assertEquals(Duration.ofMinutes(10), request.timeout());
        assertEquals(Duration.ofSeconds(10), request.pollInterval());
        assertNull(request.image());

        VideoRequest changed = request.withImage(PNG).withSeconds(8).withSize("16:9").withTimeout(Duration.ofMinutes(2))
                .withPollInterval(Duration.ofSeconds(1)).withOptions(Map.of("seed", 7));
        assertEquals(PNG, changed.image());
        assertEquals(8, changed.seconds());
        assertEquals("16:9", changed.size());
        assertEquals(Duration.ofMinutes(2), changed.timeout());
        assertEquals(Duration.ofSeconds(1), changed.pollInterval());
        assertEquals(7, changed.options().get("seed"));
        assertThrows(IllegalArgumentException.class, () -> VideoRequest.of(" "));
    }

    @Test
    void httpOptionsValidateAndHaveDefaults() {
        HttpOptions defaults = HttpOptions.defaults();
        assertEquals(3, defaults.maxAttempts());
        assertEquals(Duration.ofSeconds(10), defaults.connectTimeout());
        assertThrows(IllegalArgumentException.class, () -> new HttpOptions(Duration.ZERO, Duration.ZERO, 0, Duration.ZERO));
        assertThrows(NullPointerException.class, () -> new HttpOptions(null, Duration.ZERO, 1, Duration.ZERO));
    }

    @Test
    void modelClientDefaults() {
        ModelClient client = new TestModelProviders.FakeClient(null, "m");
        assertEquals(ModelCapabilities.textOnly(), client.capabilities());
        assertEquals(3, client.countTokens(List.of(new Message(Role.USER, "twelve chars", null))), "about four characters a token");
    }
}
