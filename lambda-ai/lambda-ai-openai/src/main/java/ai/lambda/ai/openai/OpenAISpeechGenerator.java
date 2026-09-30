package ai.lambda.ai.openai;

import ai.lambda.ai.core.HttpOptions;
import ai.lambda.ai.core.Media;
import ai.lambda.ai.generation.SpeechGenerator;
import ai.lambda.ai.generation.SpeechRequest;
import com.openai.client.OpenAIClient;
import com.openai.models.audio.speech.SpeechCreateParams;

import java.util.Map;
import java.util.Objects;

/**
 * Text-to-speech with OpenAI's speech models (for example {@code gpt-4o-mini-tts} or
 * {@code tts-1}), through OpenAI's official Java SDK.
 *
 * <pre>
 * var tts = new OpenAISpeechGenerator(key, "gpt-4o-mini-tts");
 * tts.generateSpeech(SpeechRequest.of("Your order has shipped.").withVoice("coral")
 *         .withInstructions("Speak cheerfully")).saveTo(Path.of("shipped.mp3"));
 * </pre>
 *
 * The default voice is {@code alloy} and the default format {@code mp3}; others are
 * {@code wav}, {@code opus}, {@code aac}, {@code flac} and {@code pcm}.
 */
public final class OpenAISpeechGenerator implements SpeechGenerator {

    private static final Map<String, String> MIME_BY_FORMAT = Map.of(
            "mp3", "audio/mpeg", "wav", "audio/wav", "opus", "audio/opus", "aac", "audio/aac",
            "flac", "audio/flac", "pcm", "audio/pcm");

    private final OpenAIClient client;
    private final String model;

    public OpenAISpeechGenerator(String apiKey, String model) {
        this(apiKey, model, HttpOptions.defaults(), null);
    }

    /** @param baseUrl another API root, or null for OpenAI's own */
    public OpenAISpeechGenerator(String apiKey, String model, HttpOptions options, String baseUrl) {
        this(OpenAIClients.create(apiKey, options, baseUrl), model);
    }

    public OpenAISpeechGenerator(OpenAIClient client, String model) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.model = Objects.requireNonNull(model, "model must not be null");
    }

    @Override
    public Media generateSpeech(SpeechRequest request) {
        String format = request.format() != null ? request.format().toLowerCase(java.util.Locale.ROOT) : "mp3";
        String mime = MIME_BY_FORMAT.get(format);
        if (mime == null) throw new IllegalArgumentException("Unknown audio format '" + format + "'; use one of " + MIME_BY_FORMAT.keySet());

        SpeechCreateParams.Builder params = SpeechCreateParams.builder()
                .model(model)
                .input(request.text())
                .voice(request.voice() != null ? request.voice() : "alloy")
                .responseFormat(SpeechCreateParams.ResponseFormat.of(format));
        if (request.instructions() != null) params.instructions(request.instructions());
        OpenAIClients.putOptions(request.options(), params::putAdditionalBodyProperty);
        return Media.of(OpenAIClients.bytes(client.audio().speech().create(params.build())), mime);
    }
}
