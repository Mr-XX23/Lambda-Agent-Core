package ai.lambda.ai.generation;

import ai.lambda.ai.core.Media;

/** Turns text into spoken audio. */
public interface SpeechGenerator {

    /** Returns the audio (for example MP3 or WAV). */
    Media generateSpeech(SpeechRequest request);

    default Media generateSpeech(String text) {
        return generateSpeech(SpeechRequest.of(text));
    }
}
