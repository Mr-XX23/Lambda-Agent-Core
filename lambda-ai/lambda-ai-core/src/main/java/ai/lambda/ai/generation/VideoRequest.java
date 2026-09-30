package ai.lambda.ai.generation;

import ai.lambda.ai.core.Media;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * What video to create.
 *
 * @param prompt       what the video should show
 * @param image        a starting frame, or null
 * @param seconds      length in seconds, or null for the provider's default
 * @param size         a resolution such as {@code "1280x720"} or an aspect ratio such as
 *                     {@code "16:9"}, depending on the provider; null for the default
 * @param timeout      how long to wait for the video (default 10 minutes)
 * @param pollInterval how often to check whether it is ready (default 10 seconds)
 * @param options      extra provider-specific request fields, sent as-is
 */
public record VideoRequest(String prompt, Media image, Integer seconds, String size, Duration timeout,
                           Duration pollInterval, Map<String, Object> options) {

    public VideoRequest {
        Objects.requireNonNull(prompt, "prompt must not be null");
        if (prompt.isBlank()) throw new IllegalArgumentException("prompt must not be blank");
        timeout = timeout == null ? Duration.ofMinutes(10) : timeout;
        pollInterval = pollInterval == null ? Duration.ofSeconds(10) : pollInterval;
        options = options == null ? Map.of() : Map.copyOf(options);
    }

    public static VideoRequest of(String prompt) {
        return new VideoRequest(prompt, null, null, null, null, null, Map.of());
    }

    public VideoRequest withImage(Media image) {
        return new VideoRequest(prompt, image, seconds, size, timeout, pollInterval, options);
    }

    public VideoRequest withSeconds(int seconds) {
        return new VideoRequest(prompt, image, seconds, size, timeout, pollInterval, options);
    }

    public VideoRequest withSize(String size) {
        return new VideoRequest(prompt, image, seconds, size, timeout, pollInterval, options);
    }

    public VideoRequest withTimeout(Duration timeout) {
        return new VideoRequest(prompt, image, seconds, size, timeout, pollInterval, options);
    }

    public VideoRequest withPollInterval(Duration pollInterval) {
        return new VideoRequest(prompt, image, seconds, size, timeout, pollInterval, options);
    }

    public VideoRequest withOptions(Map<String, Object> options) {
        return new VideoRequest(prompt, image, seconds, size, timeout, pollInterval, options);
    }
}
