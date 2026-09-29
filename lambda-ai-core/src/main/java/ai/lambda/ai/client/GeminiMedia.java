package ai.lambda.ai.client;

import ai.lambda.ai.core.Media;
import ai.lambda.ai.generation.ImageGenerator;
import ai.lambda.ai.generation.ImageRequest;
import ai.lambda.ai.generation.SpeechGenerator;
import ai.lambda.ai.generation.SpeechRequest;
import ai.lambda.ai.generation.VideoGenerator;
import ai.lambda.ai.generation.VideoRequest;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Image, speech and video generation with the Gemini API:
 *
 * <pre>
 * GeminiMedia.images(key, "gemini-3.1-flash-image").generateImage("A red fox in snow");
 * GeminiMedia.speech(key, "gemini-3.8-flash-tts").generateSpeech(SpeechRequest.of("Hello!").withVoice("Kore"));
 * GeminiMedia.videos(key, "veo-3.1-generate-preview").generateVideo(VideoRequest.of("Waves at sunset").withSize("16:9"));
 * </pre>
 */
public final class GeminiMedia {

    private static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com";

    private GeminiMedia() {
    }

    public static ImageGenerator images(String apiKey, String model) {
        return new Images(new Api(apiKey, model, HttpOptions.defaults(), DEFAULT_BASE_URL));
    }

    public static SpeechGenerator speech(String apiKey, String model) {
        return new Speech(new Api(apiKey, model, HttpOptions.defaults(), DEFAULT_BASE_URL));
    }

    public static VideoGenerator videos(String apiKey, String model) {
        return new Videos(new Api(apiKey, model, HttpOptions.defaults(), DEFAULT_BASE_URL));
    }

    // Visible for tests: point at a local server.
    static Api api(String apiKey, String model, HttpOptions options, String baseUrl) {
        return new Api(apiKey, model, options, baseUrl);
    }

    static final class Api {
        final String model;
        final String baseUrl;
        final JsonHttp http;

        Api(String apiKey, String model, HttpOptions options, String baseUrl) {
            Objects.requireNonNull(apiKey, "apiKey must not be null");
            this.model = Objects.requireNonNull(model, "model must not be null");
            this.baseUrl = baseUrl;
            this.http = new JsonHttp("Gemini", options, Map.of("x-goog-api-key", apiKey));
        }

        URI method(String method) {
            return URI.create(baseUrl + "/v1beta/models/" + URLEncoder.encode(model, StandardCharsets.UTF_8) + ":" + method);
        }

        /** Calls generateContent and returns the media parts of the first candidate. */
        List<Media> generateMedia(JSONArray parts, JSONObject generationConfig) {
            JSONObject body = new JSONObject()
                    .put("contents", new JSONArray().put(new JSONObject().put("role", "user").put("parts", parts)))
                    .put("generationConfig", generationConfig);
            JSONObject response = http.postJson(method("generateContent"), body);
            JSONArray candidates = response.optJSONArray("candidates");
            if (candidates == null || candidates.isEmpty()) {
                throw new RuntimeException("Gemini returned no result: " + response);
            }
            JSONObject content = candidates.getJSONObject(0).optJSONObject("content");
            List<Media> media = new ArrayList<>();
            JSONArray resultParts = content == null ? null : content.optJSONArray("parts");
            if (resultParts != null) {
                for (int i = 0; i < resultParts.length(); i++) {
                    JSONObject inline = resultParts.getJSONObject(i).optJSONObject("inlineData");
                    if (inline != null) media.add(toMedia(inline.getString("mimeType"), inline.getString("data")));
                }
            }
            if (media.isEmpty()) {
                String reason = candidates.getJSONObject(0).optString("finishReason", "unknown");
                throw new RuntimeException("Gemini returned no media (finish reason: " + reason + ")");
            }
            return media;
        }
    }

    private static final Pattern RATE = Pattern.compile("rate=(\\d+)");

    /**
     * Gemini returns WAV from newer speech models and headerless 16-bit PCM ({@code audio/L16})
     * from older ones; the latter is wrapped in a WAV header so it plays anywhere.
     */
    static Media toMedia(String mimeType, String base64) {
        String type = mimeType.toLowerCase(Locale.ROOT);
        byte[] data = java.util.Base64.getDecoder().decode(base64);
        if (type.startsWith("audio/l16") || type.startsWith("audio/pcm")) {
            Matcher rate = RATE.matcher(type);
            return Media.of(wav(data, rate.find() ? Integer.parseInt(rate.group(1)) : 24000, 1, 16), "audio/wav");
        }
        return Media.of(data, type.split(";")[0].trim());
    }

    static byte[] wav(byte[] pcm, int sampleRate, int channels, int bitsPerSample) {
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + pcm.length)
                .put("WAVE".getBytes(StandardCharsets.US_ASCII))
                .put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1)
                .putShort((short) channels).putInt(sampleRate).putInt(byteRate)
                .putShort((short) (channels * bitsPerSample / 8)).putShort((short) bitsPerSample)
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(pcm.length);
        ByteArrayOutputStream out = new ByteArrayOutputStream(44 + pcm.length);
        out.writeBytes(header.array());
        out.writeBytes(pcm);
        return out.toByteArray();
    }

    private static JSONObject inline(Media media) {
        if (!media.hasData()) throw new IllegalArgumentException("Gemini needs image bytes; load the image with Media.fromFile");
        return new JSONObject().put("inlineData", new JSONObject().put("mimeType", media.mimeType()).put("data", media.base64()));
    }

    /** Image generation with Gemini image models ("Nano Banana"), for example {@code gemini-3.1-flash-image}. */
    static final class Images implements ImageGenerator {
        private static final Pattern IMAGE_SIZE = Pattern.compile("512|[124]K", Pattern.CASE_INSENSITIVE);
        private final Api api;

        Images(Api api) {
            this.api = api;
        }

        /**
         * {@code size} may be an aspect ratio ({@code "16:9"}) or an image size ({@code "1K"},
         * {@code "2K"}, {@code "4K"}); reference images are sent along with the prompt for editing.
         */
        @Override
        public List<Media> generateImages(ImageRequest request) {
            JSONArray parts = new JSONArray().put(new JSONObject().put("text", request.prompt()));
            for (Media image : request.referenceImages()) parts.put(inline(image));

            JSONObject imageConfig = new JSONObject();
            if (request.size() != null) {
                if (request.size().contains(":")) imageConfig.put("aspectRatio", request.size());
                else if (IMAGE_SIZE.matcher(request.size()).matches()) imageConfig.put("imageSize", request.size().toUpperCase(Locale.ROOT));
                else throw new IllegalArgumentException("Gemini image size must be an aspect ratio like 16:9 or 512, 1K, 2K or 4K");
            }
            JSONObject config = new JSONObject().put("responseModalities", new JSONArray().put("IMAGE"));
            if (!imageConfig.isEmpty()) config.put("imageConfig", imageConfig);
            request.options().forEach(config::put);

            List<Media> images = new ArrayList<>();
            for (int i = 0; i < request.count(); i++) {
                api.generateMedia(parts, config).stream().filter(m -> m.mimeType().startsWith("image/")).forEach(images::add);
            }
            return images;
        }
    }

    /** Text-to-speech with Gemini TTS models, for example {@code gemini-3.8-flash-tts}. Default voice: Kore. */
    static final class Speech implements SpeechGenerator {
        private final Api api;

        Speech(Api api) {
            this.api = api;
        }

        @Override
        public Media generateSpeech(SpeechRequest request) {
            if (request.format() != null && !request.format().equalsIgnoreCase("wav")) {
                throw new IllegalArgumentException("Gemini speech is returned as WAV");
            }
            String text = request.instructions() == null ? request.text() : request.instructions() + ": " + request.text();
            JSONObject voice = new JSONObject().put("prebuiltVoiceConfig",
                    new JSONObject().put("voiceName", request.voice() != null ? request.voice() : "Kore"));
            JSONObject config = new JSONObject()
                    .put("responseModalities", new JSONArray().put("AUDIO"))
                    .put("speechConfig", new JSONObject().put("voiceConfig", voice));
            request.options().forEach(config::put);
            return api.generateMedia(new JSONArray().put(new JSONObject().put("text", text)), config).get(0);
        }
    }

    /** Video generation with Veo, for example {@code veo-3.1-generate-preview}. */
    static final class Videos implements VideoGenerator {
        private static final Pattern RESOLUTION = Pattern.compile("\\d+p|4k", Pattern.CASE_INSENSITIVE);
        private final Api api;

        Videos(Api api) {
            this.api = api;
        }

        /**
         * {@code size} may be an aspect ratio ({@code "16:9"}, {@code "9:16"}) or a resolution
         * ({@code "720p"}, {@code "1080p"}, {@code "4k"}); {@code seconds} is 4, 6 or 8.
         */
        @Override
        public Media generateVideo(VideoRequest request) {
            JSONObject instance = new JSONObject().put("prompt", request.prompt());
            if (request.image() != null) {
                Media image = request.image();
                if (!image.hasData()) throw new IllegalArgumentException("Veo needs the start image as bytes");
                instance.put("image", inline(image));
            }
            JSONObject parameters = new JSONObject();
            if (request.seconds() != null) parameters.put("durationSeconds", String.valueOf(request.seconds()));
            if (request.size() != null) {
                if (request.size().contains(":")) parameters.put("aspectRatio", request.size());
                else if (RESOLUTION.matcher(request.size()).matches()) parameters.put("resolution", request.size().toLowerCase(Locale.ROOT));
                else throw new IllegalArgumentException("Veo size must be an aspect ratio like 16:9 or a resolution like 720p");
            }
            request.options().forEach(parameters::put);

            JSONObject operation = api.http.postJson(api.method("predictLongRunning"),
                    new JSONObject().put("instances", new JSONArray().put(instance)).put("parameters", parameters));
            String name = operation.getString("name");

            Instant deadline = Instant.now().plus(request.timeout());
            while (!operation.optBoolean("done", false)) {
                if (Instant.now().isAfter(deadline)) {
                    throw new RuntimeException("Veo video was not ready after " + request.timeout() + " (operation " + name + ")");
                }
                sleep(request.pollInterval());
                operation = api.http.getJson(URI.create(api.baseUrl + "/v1beta/" + name));
            }
            if (operation.has("error")) {
                throw new RuntimeException("Veo failed: " + operation.getJSONObject("error").optString("message", operation.toString()));
            }
            String uri = operation.getJSONObject("response").getJSONObject("generateVideoResponse")
                    .getJSONArray("generatedSamples").getJSONObject(0).getJSONObject("video").getString("uri");
            JsonHttp.Binary video = api.http.download(URI.create(uri));
            String type = video.contentType().startsWith("video/") ? video.contentType() : "video/mp4";
            return Media.of(video.data(), type);
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
