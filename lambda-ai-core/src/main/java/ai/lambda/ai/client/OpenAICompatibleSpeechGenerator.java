package ai.lambda.ai.client;

import ai.lambda.ai.core.Media;
import ai.lambda.ai.generation.SpeechGenerator;
import ai.lambda.ai.generation.SpeechRequest;
import org.json.JSONObject;

import java.net.URI;
import java.util.Map;
import java.util.Objects;

/**
 * Text-to-speech through the OpenAI Audio API ({@code POST /audio/speech}), for example with
 * {@code gpt-4o-mini-tts} or {@code tts-1}.
 *
 * <pre>
 * var tts = new OpenAICompatibleSpeechGenerator(OpenAICompatibleProvider.OPENAI, key, "gpt-4o-mini-tts");
 * tts.generateSpeech(SpeechRequest.of("Your order has shipped.").withVoice("coral")
 *         .withInstructions("Speak cheerfully")).saveTo(Path.of("shipped.mp3"));
 * </pre>
 *
 * The default voice is {@code alloy} and the default format {@code mp3}; others are
 * {@code wav}, {@code opus}, {@code aac}, {@code flac} and {@code pcm}.
 */
public final class OpenAICompatibleSpeechGenerator implements SpeechGenerator {

    private static final Map<String, String> MIME_BY_FORMAT = Map.of(
            "mp3", "audio/mpeg", "wav", "audio/wav", "opus", "audio/opus", "aac", "audio/aac",
            "flac", "audio/flac", "pcm", "audio/pcm");

    private final OpenAICompatibleProvider provider;
    private final String model;
    private final JsonHttp http;

    public OpenAICompatibleSpeechGenerator(OpenAICompatibleProvider provider, String apiKey, String model) {
        this(provider, apiKey, model, HttpOptions.defaults());
    }

    public OpenAICompatibleSpeechGenerator(OpenAICompatibleProvider provider, String apiKey, String model, HttpOptions options) {
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
        this.model = Objects.requireNonNull(model, "model must not be null");
        Map<String, String> headers = JsonHttp.bearer(apiKey);
        headers.putAll(provider.headers());
        this.http = new JsonHttp(provider.name(), options, headers);
    }

    @Override
    public Media generateSpeech(SpeechRequest request) {
        String format = request.format() != null ? request.format() : "mp3";
        String mime = MIME_BY_FORMAT.get(format);
        if (mime == null) throw new IllegalArgumentException("Unknown audio format '" + format + "'; use one of " + MIME_BY_FORMAT.keySet());

        JSONObject body = new JSONObject()
                .put("model", model)
                .put("input", request.text())
                .put("voice", request.voice() != null ? request.voice() : "alloy")
                .put("response_format", format);
        if (request.instructions() != null) body.put("instructions", request.instructions());
        request.options().forEach(body::put);

        JsonHttp.Binary audio = http.postForBytes(URI.create(provider.baseUrl() + "/audio/speech"), body);
        return Media.of(audio.data(), mime);
    }
}
