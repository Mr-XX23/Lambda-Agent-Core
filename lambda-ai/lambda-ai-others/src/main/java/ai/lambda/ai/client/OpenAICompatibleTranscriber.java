package ai.lambda.ai.client;

import ai.lambda.ai.core.HttpOptions;
import ai.lambda.ai.core.Media;
import ai.lambda.ai.core.Modality;
import ai.lambda.ai.generation.Transcriber;
import org.json.JSONObject;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Speech-to-text through the OpenAI Audio API ({@code POST /audio/transcriptions}), for example
 * with {@code gpt-transcribe} or {@code whisper-1}, and compatible providers such as Mistral.
 *
 * <pre>
 * var stt = new OpenAICompatibleTranscriber(OpenAICompatibleProvider.OPENAI, key, "gpt-transcribe");
 * String text = stt.transcribe(Media.fromFile(Path.of("meeting.m4a")), "en");
 * </pre>
 */
public final class OpenAICompatibleTranscriber implements Transcriber {

    private final OpenAICompatibleProvider provider;
    private final String model;
    private final JsonHttp http;

    public OpenAICompatibleTranscriber(OpenAICompatibleProvider provider, String apiKey, String model) {
        this(provider, apiKey, model, HttpOptions.defaults());
    }

    public OpenAICompatibleTranscriber(OpenAICompatibleProvider provider, String apiKey, String model, HttpOptions options) {
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
        this.model = Objects.requireNonNull(model, "model must not be null");
        Map<String, String> headers = JsonHttp.bearer(apiKey);
        headers.putAll(provider.headers());
        this.http = new JsonHttp(provider.name(), options, headers);
    }

    @Override
    public String transcribe(Media audio, String language) {
        Objects.requireNonNull(audio, "audio must not be null");
        if (audio.modality() != Modality.AUDIO && audio.modality() != Modality.VIDEO) {
            throw new IllegalArgumentException("Expected an audio file, got " + audio.mimeType());
        }
        if (!audio.hasData()) throw new IllegalArgumentException("Load the audio with Media.fromFile; URLs are not supported");

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("model", model);
        if (provider.dialect() == OpenAICompatibleProvider.Dialect.OPENAI
                || provider.dialect() == OpenAICompatibleProvider.Dialect.OPENROUTER) {
            fields.put("response_format", "json");
        }
        if (language != null && !language.isBlank()) fields.put("language", language);

        JSONObject response = http.postMultipart(URI.create(provider.baseUrl() + "/audio/transcriptions"),
                fields, "file", audio);
        return response.getString("text");
    }
}
