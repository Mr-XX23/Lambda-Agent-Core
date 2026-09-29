package ai.lambda.ai.generation;

import ai.lambda.ai.core.Media;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What image to create.
 *
 * @param prompt          what the image should show
 * @param referenceImages images to edit or take inspiration from (not every provider supports them)
 * @param size            a size such as {@code "1024x1024"}, or an aspect ratio such as {@code "16:9"},
 *                        depending on the provider; null for the provider's default
 * @param count           how many images to create
 * @param options         extra provider-specific request fields, sent as-is
 */
public record ImageRequest(String prompt, List<Media> referenceImages, String size, int count,
                           Map<String, Object> options) {

    public ImageRequest {
        Objects.requireNonNull(prompt, "prompt must not be null");
        if (prompt.isBlank()) throw new IllegalArgumentException("prompt must not be blank");
        referenceImages = referenceImages == null ? List.of() : List.copyOf(referenceImages);
        if (count < 1) throw new IllegalArgumentException("count must be at least 1");
        options = options == null ? Map.of() : Map.copyOf(options);
    }

    public static ImageRequest of(String prompt) {
        return new ImageRequest(prompt, List.of(), null, 1, Map.of());
    }

    public ImageRequest withSize(String size) {
        return new ImageRequest(prompt, referenceImages, size, count, options);
    }

    public ImageRequest withCount(int count) {
        return new ImageRequest(prompt, referenceImages, size, count, options);
    }

    public ImageRequest withReferenceImages(Media... images) {
        return new ImageRequest(prompt, List.of(images), size, count, options);
    }

    public ImageRequest withOptions(Map<String, Object> options) {
        return new ImageRequest(prompt, referenceImages, size, count, options);
    }
}
