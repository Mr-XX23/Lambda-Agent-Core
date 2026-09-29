package ai.lambda.ai.generation;

import ai.lambda.ai.core.Media;

import java.util.List;

/** Creates images from a text prompt (and optionally reference images). */
public interface ImageGenerator {

    /** Returns the generated images, as bytes (for example PNG). */
    List<Media> generateImages(ImageRequest request);

    default Media generateImage(String prompt) {
        return generateImages(ImageRequest.of(prompt)).get(0);
    }
}
