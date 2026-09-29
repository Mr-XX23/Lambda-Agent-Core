package ai.lambda.agent.prebuilt;

import ai.lambda.agent.core.AgentTool;
import ai.lambda.agent.core.ToolArgumentValidator;
import ai.lambda.agent.core.ToolCapability;
import ai.lambda.agent.core.ToolInvocationContext;
import ai.lambda.agent.core.ToolPolicy;
import ai.lambda.agent.core.ToolResult;
import ai.lambda.ai.core.Media;
import ai.lambda.ai.generation.ImageGenerator;
import ai.lambda.ai.generation.ImageRequest;
import ai.lambda.ai.generation.SpeechGenerator;
import ai.lambda.ai.generation.SpeechRequest;
import ai.lambda.ai.generation.Transcriber;
import ai.lambda.ai.generation.VideoGenerator;
import ai.lambda.ai.generation.VideoRequest;
import org.json.JSONObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Agent tools that create or read media, so an agent can make images, speech and videos, or
 * transcribe recordings, as part of its work. Generated files are saved to an output folder and
 * the tool tells the model where; transcription reads audio files from inside a root folder only.
 *
 * <pre>
 * var images = new OpenAIImageGenerator(OpenAICompatibleProvider.OPENAI, key, "gpt-image-2");
 * List&lt;AgentTool&gt; tools = List.of(MediaTools.generateImage(images, Path.of("out")));
 * </pre>
 */
public final class MediaTools {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final AtomicInteger COUNTER = new AtomicInteger();

    private MediaTools() {
    }

    /** A {@code generate_image} tool. */
    public static AgentTool generateImage(ImageGenerator generator, Path outputDir) {
        Objects.requireNonNull(generator, "generator must not be null");
        return new MediaTool("generate_image",
                "Creates images from a text description and saves them as files. Returns the file paths.",
                """
                {"type": "object",
                 "properties": {
                   "prompt": {"type": "string", "description": "A detailed description of the image"},
                   "size": {"type": "string", "description": "Optional size or aspect ratio, for example 1024x1024 or 16:9"},
                   "count": {"type": "integer", "description": "How many images to create (1-4)", "minimum": 1, "maximum": 4}
                 },
                 "required": ["prompt"]}
                """,
                Duration.ofMinutes(3), Set.of(ToolCapability.WRITE, ToolCapability.NETWORK), "prompt",
                args -> {
                    ImageRequest request = ImageRequest.of(args.getString("prompt"))
                            .withCount(Math.max(1, Math.min(4, args.optInt("count", 1))));
                    if (!args.optString("size").isBlank()) request = request.withSize(args.getString("size"));
                    return save(generator.generateImages(request), outputDir, "image");
                });
    }

    /** A {@code generate_speech} tool. */
    public static AgentTool generateSpeech(SpeechGenerator generator, Path outputDir) {
        Objects.requireNonNull(generator, "generator must not be null");
        return new MediaTool("generate_speech",
                "Turns text into spoken audio and saves it as a file. Returns the file path.",
                """
                {"type": "object",
                 "properties": {
                   "text": {"type": "string", "description": "The words to speak"},
                   "voice": {"type": "string", "description": "Optional voice name"},
                   "instructions": {"type": "string", "description": "Optional tone or style, for example 'calm and slow'"}
                 },
                 "required": ["text"]}
                """,
                Duration.ofMinutes(2), Set.of(ToolCapability.WRITE, ToolCapability.NETWORK), "text",
                args -> {
                    SpeechRequest request = SpeechRequest.of(args.getString("text"));
                    if (!args.optString("voice").isBlank()) request = request.withVoice(args.getString("voice"));
                    if (!args.optString("instructions").isBlank()) request = request.withInstructions(args.getString("instructions"));
                    return save(List.of(generator.generateSpeech(request)), outputDir, "speech");
                });
    }

    /** A {@code generate_video} tool. Video generation can take several minutes. */
    public static AgentTool generateVideo(VideoGenerator generator, Path outputDir) {
        Objects.requireNonNull(generator, "generator must not be null");
        return new MediaTool("generate_video",
                "Creates a short video from a text description and saves it as a file. Takes a few minutes. "
                        + "Returns the file path.",
                """
                {"type": "object",
                 "properties": {
                   "prompt": {"type": "string", "description": "A detailed description of the video, including motion and sound"},
                   "seconds": {"type": "integer", "description": "Optional length in seconds"},
                   "size": {"type": "string", "description": "Optional aspect ratio or resolution, for example 16:9 or 720p"}
                 },
                 "required": ["prompt"]}
                """,
                Duration.ofMinutes(15), Set.of(ToolCapability.WRITE, ToolCapability.NETWORK), "prompt",
                args -> {
                    VideoRequest request = VideoRequest.of(args.getString("prompt")).withTimeout(Duration.ofMinutes(14));
                    if (args.has("seconds")) request = request.withSeconds(args.getInt("seconds"));
                    if (!args.optString("size").isBlank()) request = request.withSize(args.getString("size"));
                    return save(List.of(generator.generateVideo(request)), outputDir, "video");
                });
    }

    /** A {@code transcribe_audio} tool that reads audio files inside {@code root}. */
    public static AgentTool transcribeAudio(Transcriber transcriber, Path root) {
        Objects.requireNonNull(transcriber, "transcriber must not be null");
        Path base = root.toAbsolutePath().normalize();
        return new MediaTool("transcribe_audio",
                "Turns speech in an audio file into text. Returns the transcript.",
                """
                {"type": "object",
                 "properties": {
                   "path": {"type": "string", "description": "Path of the audio file, relative to the workspace"},
                   "language": {"type": "string", "description": "Optional ISO-639-1 language code, for example en"}
                 },
                 "required": ["path"]}
                """,
                Duration.ofMinutes(5), Set.of(ToolCapability.READ, ToolCapability.NETWORK), "path",
                args -> {
                    Path file = base.resolve(args.getString("path")).normalize();
                    if (!file.startsWith(base)) throw new SecurityException("Path is outside the workspace");
                    if (!Files.isRegularFile(file)) throw new IllegalArgumentException("No such file: " + args.getString("path"));
                    if (!file.toRealPath().startsWith(base.toRealPath())) {
                        throw new SecurityException("Path resolves outside the workspace");
                    }
                    String language = args.optString("language");
                    return transcriber.transcribe(Media.fromFile(file), language.isBlank() ? null : language);
                });
    }

    private static String save(List<Media> media, Path outputDir, String kind) {
        List<String> paths = new ArrayList<>();
        String stamp = LocalDateTime.now().format(STAMP);
        for (Media item : media) {
            Path file = outputDir.resolve(kind + "-" + stamp + "-" + COUNTER.incrementAndGet() + "." + item.fileExtension());
            paths.add(item.saveTo(file).toAbsolutePath().toString());
        }
        return "Saved " + paths.size() + " file(s): " + String.join(", ", paths);
    }

    private interface Action {
        String run(JSONObject args) throws Exception;
    }

    private static final class MediaTool implements AgentTool {
        private final String name;
        private final String description;
        private final String schema;
        private final ToolPolicy policy;
        private final String requiredField;
        private final Action action;

        MediaTool(String name, String description, String schema, Duration timeout, Set<ToolCapability> capabilities,
                  String requiredField, Action action) {
            this.name = name;
            this.description = description;
            this.schema = schema;
            this.policy = new ToolPolicy(false, timeout, 64 * 1024, capabilities);
            this.requiredField = requiredField;
            this.action = action;
        }

        public String getName() { return name; }
        public String getDescription() { return description; }
        public String getJsonSchema() { return schema; }
        public ToolPolicy getPolicy() { return policy; }

        public ToolArgumentValidator getArgumentValidator() {
            return argumentsJson -> {
                if (new JSONObject(argumentsJson).optString(requiredField).isBlank()) {
                    throw new IllegalArgumentException(requiredField + " is required");
                }
            };
        }

        public ToolResult execute(ToolInvocationContext context) throws Exception {
            return new ToolResult(action.run(new JSONObject(context.getArgumentsJson())), Map.of());
        }
    }
}
