package ai.lambda.ai.client;

import ai.lambda.ai.core.HttpOptions;
import ai.lambda.ai.core.Media;
import ai.lambda.ai.generation.SpeechGenerator;
import ai.lambda.ai.generation.SpeechRequest;
import ai.lambda.ai.generation.Transcriber;
import ai.lambda.ai.generation.VideoGenerator;
import ai.lambda.ai.generation.VideoRequest;
import org.json.JSONObject;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * xAI speech, transcription and video generation. For xAI images use
 * {@code new OpenAICompatibleImageGenerator(OpenAICompatibleProvider.XAI, key, "grok-imagine-image-2.0")}.
 *
 * <pre>
 * XaiMedia.speech(key).generateSpeech(SpeechRequest.of("Hello!").withVoice("eve"));
 * XaiMedia.transcriber(key, "grok-voice-transcribe-2.0").transcribe(Media.fromFile(Path.of("call.mp3")));
 * XaiMedia.videos(key, "grok-imagine-video-1.5").generateVideo(VideoRequest.of("A paper boat").withSeconds(6));
 * </pre>
 */
public final class XaiMedia {

    private static final String BASE_URL = "https://api.x.ai/v1";

    private XaiMedia() {
    }

    public static SpeechGenerator speech(String apiKey) {
        return new Speech(http(apiKey, HttpOptions.defaults()), BASE_URL);
    }

    public static Transcriber transcriber(String apiKey, String model) {
        return new Stt(http(apiKey, HttpOptions.defaults()), BASE_URL, model);
    }

    public static VideoGenerator videos(String apiKey, String model) {
        return new Videos(http(apiKey, HttpOptions.defaults()), BASE_URL, model);
    }

    static JsonHttp http(String apiKey, HttpOptions options) {
        Objects.requireNonNull(apiKey, "apiKey must not be null");
        return new JsonHttp("xAI", options, JsonHttp.bearer(apiKey));
    }

    /**
     * Text-to-speech ({@code POST /tts}). Default voice {@code eve}, default format {@code mp3}
     * (also {@code wav}); the language is detected unless given as option {@code language}.
     */
    static final class Speech implements SpeechGenerator {
        private static final Map<String, String> MIME = Map.of("mp3", "audio/mpeg", "wav", "audio/wav");
        private final JsonHttp http;
        private final String baseUrl;

        Speech(JsonHttp http, String baseUrl) {
            this.http = http;
            this.baseUrl = baseUrl;
        }

        @Override
        public Media generateSpeech(SpeechRequest request) {
            String format = request.format() != null ? request.format().toLowerCase(Locale.ROOT) : "mp3";
            if (!MIME.containsKey(format)) throw new IllegalArgumentException("xAI speech format must be mp3 or wav");
            JSONObject body = new JSONObject()
                    .put("text", request.text())
                    .put("language", "auto")
                    .put("voice_id", request.voice() != null ? request.voice() : "eve")
                    .put("output_format", new JSONObject().put("codec", format));
            request.options().forEach(body::put);
            JsonHttp.Binary audio = http.postForBytes(URI.create(baseUrl + "/tts"), body);
            return Media.of(audio.data(), MIME.get(format));
        }
    }

    /** Speech-to-text ({@code POST /stt}), for example with {@code grok-voice-transcribe-2.0}. */
    static final class Stt implements Transcriber {
        private final JsonHttp http;
        private final String baseUrl;
        private final String model;

        Stt(JsonHttp http, String baseUrl, String model) {
            this.http = http;
            this.baseUrl = baseUrl;
            this.model = Objects.requireNonNull(model, "model must not be null");
        }

        @Override
        public String transcribe(Media audio, String language) {
            if (!audio.hasData()) throw new IllegalArgumentException("Load the audio with Media.fromFile; URLs are not supported");
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("model", model);
            if (language != null && !language.isBlank()) fields.put("language", language);
            // xAI requires the file to be the last field, which postMultipart does.
            return http.postMultipart(URI.create(baseUrl + "/stt"), fields, "file", audio).getString("text");
        }
    }

    /**
     * Video generation ({@code POST /videos/generations}, then polling). {@code size} may be an
     * aspect ratio ({@code "16:9"}) or a resolution ({@code "480p"}, {@code "720p"},
     * {@code "1080p"}); {@code seconds} is 1 to 15.
     */
    static final class Videos implements VideoGenerator {
        private static final Set<String> FAILED = Set.of("failed", "expired");
        private final JsonHttp http;
        private final String baseUrl;
        private final String model;

        Videos(JsonHttp http, String baseUrl, String model) {
            this.http = http;
            this.baseUrl = baseUrl;
            this.model = Objects.requireNonNull(model, "model must not be null");
        }

        @Override
        public Media generateVideo(VideoRequest request) {
            JSONObject body = new JSONObject().put("model", model).put("prompt", request.prompt());
            if (request.seconds() != null) body.put("duration", request.seconds());
            if (request.size() != null) {
                body.put(request.size().contains(":") ? "aspect_ratio" : "resolution", request.size().toLowerCase(Locale.ROOT));
            }
            if (request.image() != null) body.put("image", new JSONObject().put("url", request.image().dataUrl()));
            request.options().forEach(body::put);

            String id = http.postJson(URI.create(baseUrl + "/videos/generations"), body).getString("request_id");
            Instant deadline = Instant.now().plus(request.timeout());
            while (true) {
                JSONObject status = http.getJson(URI.create(baseUrl + "/videos/" + id));
                String state = status.optString("status", "");
                if (state.equals("done")) {
                    JsonHttp.Binary video = http.download(URI.create(status.getJSONObject("video").getString("url")));
                    return Media.of(video.data(), video.contentType().startsWith("video/") ? video.contentType() : "video/mp4");
                }
                if (FAILED.contains(state)) throw new RuntimeException("xAI video " + state + ": " + status);
                if (Instant.now().isAfter(deadline)) {
                    throw new RuntimeException("xAI video was not ready after " + request.timeout() + " (request " + id + ")");
                }
                sleep(request.pollInterval());
            }
        }

        private static void sleep(Duration duration) {
            try {
                Thread.sleep(duration.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for the video", e);
            }
        }
    }
}
