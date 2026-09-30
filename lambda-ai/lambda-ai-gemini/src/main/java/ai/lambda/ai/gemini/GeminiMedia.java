package ai.lambda.ai.gemini;

import ai.lambda.ai.core.HttpOptions;
import ai.lambda.ai.core.Media;
import ai.lambda.ai.generation.ImageGenerator;
import ai.lambda.ai.generation.ImageRequest;
import ai.lambda.ai.generation.SpeechGenerator;
import ai.lambda.ai.generation.SpeechRequest;
import ai.lambda.ai.generation.VideoGenerator;
import ai.lambda.ai.generation.VideoRequest;
import com.google.genai.Client;
import com.google.genai.types.Blob;
import com.google.genai.types.Candidate;
import com.google.genai.types.Content;
import com.google.genai.types.DownloadFileConfig;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateVideosConfig;
import com.google.genai.types.GenerateVideosOperation;
import com.google.genai.types.GenerateVideosSource;
import com.google.genai.types.GeneratedVideo;
import com.google.genai.types.GetOperationConfig;
import com.google.genai.types.Image;
import com.google.genai.types.ImageConfig;
import com.google.genai.types.Part;
import com.google.genai.types.PrebuiltVoiceConfig;
import com.google.genai.types.SpeechConfig;
import com.google.genai.types.Video;
import com.google.genai.types.VoiceConfig;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Image, speech and video generation with Gemini, through Google's official Gen AI Java SDK:
 *
 * <pre>
 * GeminiMedia.images(key, "gemini-3.1-flash-image").generateImage("A red fox in snow");
 * GeminiMedia.speech(key, "gemini-3.8-flash-tts").generateSpeech(SpeechRequest.of("Hello!").withVoice("Kore"));
 * GeminiMedia.videos(key, "veo-3.1-generate-preview").generateVideo(VideoRequest.of("Waves at sunset").withSize("16:9"));
 * </pre>
 *
 * A request's {@code options} are sent as extra top-level fields of the API request.
 */
public final class GeminiMedia {

    /** The limit for each call; the SDK has no other timeout. */
    private static final Duration TIMEOUT = HttpOptions.defaults().requestTimeout();

    private GeminiMedia() {
    }

    public static ImageGenerator images(String apiKey, String model) {
        return images(GeminiClients.create(apiKey, HttpOptions.defaults(), null), model);
    }

    public static SpeechGenerator speech(String apiKey, String model) {
        return speech(GeminiClients.create(apiKey, HttpOptions.defaults(), null), model);
    }

    public static VideoGenerator videos(String apiKey, String model) {
        return videos(GeminiClients.create(apiKey, HttpOptions.defaults(), null), model);
    }

    /** Uses an SDK client you configured yourself (for example for Vertex AI, or another base URL). */
    public static ImageGenerator images(Client client, String model) {
        return new Images(client, model);
    }

    public static SpeechGenerator speech(Client client, String model) {
        return new Speech(client, model);
    }

    public static VideoGenerator videos(Client client, String model) {
        return new Videos(client, model);
    }

    /** An SDK client with these options, for the factories above; {@code baseUrl} null for Google's own. */
    public static Client client(String apiKey, HttpOptions options, String baseUrl) {
        return GeminiClients.create(apiKey, options, baseUrl);
    }

    /** Calls generateContent and returns the media parts of the first candidate. */
    private static List<Media> generateMedia(Client client, String model, List<Part> parts, GenerateContentConfig config) {
        GenerateContentResponse response = client.models.generateContent(model,
                List.of(Content.builder().role("user").parts(parts).build()), config);
        List<Candidate> candidates = response.candidates().orElse(List.of());
        if (candidates.isEmpty()) throw new RuntimeException("Gemini returned no result");
        List<Media> media = new ArrayList<>();
        for (Part part : candidates.get(0).content().flatMap(Content::parts).orElse(List.of())) {
            part.inlineData().ifPresent(blob -> media.add(toMedia(blob.mimeType().orElse("application/octet-stream"),
                    blob.data().orElse(new byte[0]))));
        }
        if (media.isEmpty()) {
            String reason = candidates.get(0).finishReason().map(Object::toString).orElse("unknown");
            throw new RuntimeException("Gemini returned no media (finish reason: " + reason + ")");
        }
        return media;
    }

    private static final Pattern RATE = Pattern.compile("rate=(\\d+)");

    /**
     * Gemini returns WAV from newer speech models and headerless 16-bit PCM ({@code audio/L16})
     * from older ones; the latter is wrapped in a WAV header so it plays anywhere.
     */
    static Media toMedia(String mimeType, byte[] data) {
        String type = mimeType.toLowerCase(Locale.ROOT);
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

    private static byte[] bytesOf(Media media, String what) {
        if (!media.hasData()) throw new IllegalArgumentException("Gemini needs the " + what + " as bytes; load it with Media.fromFile");
        return media.readBytes(bytes -> bytes); // only read, to encode the request
    }

    /** Image generation with Gemini image models ("Nano Banana"), for example {@code gemini-3.1-flash-image}. */
    private record Images(Client client, String model) implements ImageGenerator {
        private static final Pattern IMAGE_SIZE = Pattern.compile("512|[124]K", Pattern.CASE_INSENSITIVE);

        Images {
            Objects.requireNonNull(client, "client must not be null");
            Objects.requireNonNull(model, "model must not be null");
        }

        /**
         * {@code size} may be an aspect ratio ({@code "16:9"}) or an image size ({@code "1K"},
         * {@code "2K"}, {@code "4K"}); reference images are sent along with the prompt for editing.
         */
        @Override
        public List<Media> generateImages(ImageRequest request) {
            List<Part> parts = new ArrayList<>();
            parts.add(Part.builder().text(request.prompt()).build());
            for (Media image : request.referenceImages()) {
                parts.add(Part.builder().inlineData(Blob.builder().mimeType(image.mimeType())
                        .data(bytesOf(image, "reference image")).build()).build());
            }
            GenerateContentConfig.Builder config = GenerateContentConfig.builder().responseModalities(List.of("IMAGE"));
            if (request.size() != null) {
                ImageConfig.Builder image = ImageConfig.builder();
                if (request.size().contains(":")) image.aspectRatio(request.size());
                else if (IMAGE_SIZE.matcher(request.size()).matches()) image.imageSize(request.size().toUpperCase(Locale.ROOT));
                else throw new IllegalArgumentException("Gemini image size must be an aspect ratio like 16:9 or 512, 1K, 2K or 4K");
                config.imageConfig(image.build());
            }
            config.httpOptions(GeminiClients.requestOptions(TIMEOUT, request.options()));

            List<Media> images = new ArrayList<>();
            for (int i = 0; i < request.count(); i++) {
                generateMedia(client, model, parts, config.build()).stream()
                        .filter(m -> m.mimeType().startsWith("image/")).forEach(images::add);
            }
            return images;
        }
    }

    /** Text-to-speech with Gemini TTS models, for example {@code gemini-3.8-flash-tts}. Default voice: Kore. */
    private record Speech(Client client, String model) implements SpeechGenerator {
        Speech {
            Objects.requireNonNull(client, "client must not be null");
            Objects.requireNonNull(model, "model must not be null");
        }

        @Override
        public Media generateSpeech(SpeechRequest request) {
            if (request.format() != null && !request.format().equalsIgnoreCase("wav")) {
                throw new IllegalArgumentException("Gemini speech is returned as WAV");
            }
            String text = request.instructions() == null ? request.text() : request.instructions() + ": " + request.text();
            GenerateContentConfig.Builder config = GenerateContentConfig.builder()
                    .responseModalities(List.of("AUDIO"))
                    .speechConfig(SpeechConfig.builder().voiceConfig(VoiceConfig.builder().prebuiltVoiceConfig(
                            PrebuiltVoiceConfig.builder().voiceName(request.voice() != null ? request.voice() : "Kore").build())
                            .build()).build());
            config.httpOptions(GeminiClients.requestOptions(TIMEOUT, request.options()));
            return generateMedia(client, model, List.of(Part.builder().text(text).build()), config.build()).get(0);
        }
    }

    /** Video generation with Veo, for example {@code veo-3.1-generate-preview}. */
    private record Videos(Client client, String model) implements VideoGenerator {
        private static final Pattern RESOLUTION = Pattern.compile("\\d+p|4k", Pattern.CASE_INSENSITIVE);

        Videos {
            Objects.requireNonNull(client, "client must not be null");
            Objects.requireNonNull(model, "model must not be null");
        }

        /**
         * {@code size} may be an aspect ratio ({@code "16:9"}, {@code "9:16"}) or a resolution
         * ({@code "720p"}, {@code "1080p"}, {@code "4k"}); {@code seconds} is 4, 6 or 8.
         */
        @Override
        public Media generateVideo(VideoRequest request) {
            GenerateVideosSource.Builder source = GenerateVideosSource.builder().prompt(request.prompt());
            if (request.image() != null) {
                Media image = request.image();
                source.image(Image.builder().imageBytes(bytesOf(image, "start image")).mimeType(image.mimeType()).build());
            }
            GenerateVideosConfig.Builder config = GenerateVideosConfig.builder();
            if (request.seconds() != null) config.durationSeconds(request.seconds());
            if (request.size() != null) {
                if (request.size().contains(":")) config.aspectRatio(request.size());
                else if (RESOLUTION.matcher(request.size()).matches()) config.resolution(request.size().toLowerCase(Locale.ROOT));
                else throw new IllegalArgumentException("Veo size must be an aspect ratio like 16:9 or a resolution like 720p");
            }
            config.httpOptions(GeminiClients.requestOptions(TIMEOUT, request.options()));

            GenerateVideosOperation operation = client.models.generateVideos(model, source.build(), config.build());
            Instant deadline = Instant.now().plus(request.timeout());
            while (!operation.done().orElse(false)) {
                if (Instant.now().isAfter(deadline)) {
                    throw new RuntimeException("Veo video was not ready after " + request.timeout()
                            + " (operation " + operation.name().orElse("?") + ")");
                }
                sleep(request.pollInterval());
                operation = client.operations.getVideosOperation(operation,
                        GetOperationConfig.builder().httpOptions(GeminiClients.requestOptions(TIMEOUT, Map.of())).build());
            }
            if (operation.error().isPresent()) {
                Object message = operation.error().get().get("message");
                throw new RuntimeException("Veo failed: " + (message != null ? message : operation.error().get()));
            }
            GeneratedVideo generated = operation.response().flatMap(r -> r.generatedVideos()).filter(v -> !v.isEmpty())
                    .map(v -> v.get(0)).orElseThrow(() -> new RuntimeException("Veo returned no video"));
            Video video = generated.video().orElseThrow(() -> new RuntimeException("Veo returned no video"));
            String type = video.mimeType().filter(t -> t.startsWith("video/")).orElse("video/mp4");
            return Media.of(video.videoBytes().orElseGet(() -> download(video)), type);
        }

        /** The SDK saves a generated video to a file; read it back and remove the file. */
        private byte[] download(Video video) {
            try {
                Path file = Files.createTempFile("veo-", ".mp4");
                try {
                    client.files.download(video, file.toString(), DownloadFileConfig.builder()
                            .httpOptions(GeminiClients.requestOptions(HttpOptions.defaults().callTimeout(), Map.of())).build());
                    return Files.readAllBytes(file);
                } finally {
                    Files.deleteIfExists(file);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Could not download the Veo video", e);
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
