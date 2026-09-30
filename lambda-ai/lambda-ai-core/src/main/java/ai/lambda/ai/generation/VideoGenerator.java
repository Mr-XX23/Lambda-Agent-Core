package ai.lambda.ai.generation;

import ai.lambda.ai.core.Media;

/**
 * Creates videos from a text prompt (and optionally a starting image). Video generation runs
 * as a background job at the provider; this call waits for it, checking every few seconds,
 * until it finishes or {@link VideoRequest#timeout()} passes.
 */
public interface VideoGenerator {

    /** Returns the video (for example MP4). */
    Media generateVideo(VideoRequest request);

    default Media generateVideo(String prompt) {
        return generateVideo(VideoRequest.of(prompt));
    }
}
