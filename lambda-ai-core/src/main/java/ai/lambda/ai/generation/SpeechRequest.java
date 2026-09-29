package ai.lambda.ai.generation;

import java.util.Map;
import java.util.Objects;

/**
 * What to say and how.
 *
 * @param text         the words to speak
 * @param voice        a provider voice name, or null for the default
 * @param instructions how to speak (tone, pace, accent), where the provider supports it
 * @param format       an audio format such as {@code "mp3"} or {@code "wav"}, or null for the default
 * @param options      extra provider-specific request fields, sent as-is
 */
public record SpeechRequest(String text, String voice, String instructions, String format, Map<String, Object> options) {

    public SpeechRequest {
        Objects.requireNonNull(text, "text must not be null");
        if (text.isBlank()) throw new IllegalArgumentException("text must not be blank");
        options = options == null ? Map.of() : Map.copyOf(options);
    }

    public static SpeechRequest of(String text) {
        return new SpeechRequest(text, null, null, null, Map.of());
    }

    public SpeechRequest withVoice(String voice) {
        return new SpeechRequest(text, voice, instructions, format, options);
    }

    public SpeechRequest withInstructions(String instructions) {
        return new SpeechRequest(text, voice, instructions, format, options);
    }

    public SpeechRequest withFormat(String format) {
        return new SpeechRequest(text, voice, instructions, format, options);
    }

    public SpeechRequest withOptions(Map<String, Object> options) {
        return new SpeechRequest(text, voice, instructions, format, options);
    }
}
