package ai.lambda.ai.client;

import ai.lambda.ai.core.HttpOptions;
import ai.lambda.ai.core.Media;
import ai.lambda.ai.generation.SpeechGenerator;
import ai.lambda.ai.generation.SpeechRequest;
import ai.lambda.ai.generation.Transcriber;
import org.json.JSONObject;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Mistral speech and transcription (Voxtral models). Mistral has no image or video generation
 * endpoint.
 *
 * <pre>
 * MistralMedia.speech(key, "voxtral-mini-tts-2603").generateSpeech(SpeechRequest.of("Bonjour !"));
 * MistralMedia.transcriber(key, "voxtral-mini-latest").transcribe(Media.fromFile(Path.of("memo.m4a")));
 * </pre>
 */
public final class MistralMedia {

    private MistralMedia() {
    }

    public static SpeechGenerator speech(String apiKey, String model) {
        return new Speech(new JsonHttp("Mistral", HttpOptions.defaults(), JsonHttp.bearer(apiKey)),
                OpenAICompatibleProvider.MISTRAL.baseUrl(), model);
    }

    /** Transcription through Mistral's OpenAI-style {@code /audio/transcriptions} endpoint. */
    public static Transcriber transcriber(String apiKey, String model) {
        return new OpenAICompatibleTranscriber(OpenAICompatibleProvider.MISTRAL, apiKey, model);
    }

    /**
     * Text-to-speech ({@code POST /audio/speech}). The voice is a Mistral voice id; the format is
     * {@code mp3} (default), {@code wav}, {@code flac}, {@code opus} or {@code pcm}.
     */
    static final class Speech implements SpeechGenerator {
        private static final Map<String, String> MIME = Map.of("mp3", "audio/mpeg", "wav", "audio/wav",
                "flac", "audio/flac", "opus", "audio/opus", "pcm", "audio/pcm");
        private final JsonHttp http;
        private final String baseUrl;
        private final String model;

        Speech(JsonHttp http, String baseUrl, String model) {
            this.http = http;
            this.baseUrl = baseUrl;
            this.model = Objects.requireNonNull(model, "model must not be null");
        }

        @Override
        public Media generateSpeech(SpeechRequest request) {
            String format = request.format() != null ? request.format().toLowerCase(Locale.ROOT) : "mp3";
            String mime = MIME.get(format);
            if (mime == null) throw new IllegalArgumentException("Mistral speech format must be one of " + MIME.keySet());
            JSONObject body = new JSONObject().put("model", model).put("input", request.text()).put("response_format", format);
            if (request.voice() != null) body.put("voice_id", request.voice());
            request.options().forEach(body::put);
            // Unlike OpenAI, Mistral returns the audio as base64 inside JSON.
            JSONObject response = http.postJson(URI.create(baseUrl + "/audio/speech"), body);
            return Media.fromBase64(response.getString("audio_data"), mime);
        }
    }
}
