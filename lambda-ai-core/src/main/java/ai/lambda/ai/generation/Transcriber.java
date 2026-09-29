package ai.lambda.ai.generation;

import ai.lambda.ai.core.Media;

/** Turns speech in an audio file into text. */
public interface Transcriber {

    /**
     * @param audio    the recording (for example MP3, WAV or M4A)
     * @param language the spoken language as an ISO-639-1 code such as {@code "en"}, or null to detect it
     */
    String transcribe(Media audio, String language);

    default String transcribe(Media audio) {
        return transcribe(audio, null);
    }
}
