package ai.lambda.ai.gemini;

import ai.lambda.ai.client.HttpOptions;
import ai.lambda.ai.core.ChatResponse;
import ai.lambda.ai.core.FinishReason;
import ai.lambda.ai.core.Media;
import ai.lambda.ai.core.Message;
import ai.lambda.ai.core.Modality;
import ai.lambda.ai.core.ModelCapabilities;
import ai.lambda.ai.core.ModelClient;
import ai.lambda.ai.core.ModelUsage;
import ai.lambda.ai.core.Role;
import ai.lambda.ai.core.ToolCall;
import ai.lambda.ai.core.ToolSchema;
import com.google.genai.Client;
import com.google.genai.ResponseStream;
import com.google.genai.types.Blob;
import com.google.genai.types.Candidate;
import com.google.genai.types.Content;
import com.google.genai.types.CountTokensConfig;
import com.google.genai.types.FileData;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionCallingConfig;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import com.google.genai.types.Tool;
import com.google.genai.types.ToolConfig;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Gemini models through Google's official Gen AI Java SDK.
 *
 * <pre>
 * ModelClient gemini = new GeminiModelClient(System.getenv("GEMINI_API_KEY"), "gemini-3.1-flash");
 * ModelClient same = Models.create("gemini:gemini-3.1-flash");      // key from GEMINI_API_KEY
 * </pre>
 *
 * Messages may carry images, audio, video and PDFs, as bytes or as URLs Gemini can fetch (such as
 * files uploaded to Google's File API). Tool calls keep Gemini's thought signatures, which must be
 * sent back with the call on the next turn.
 */
public final class GeminiModelClient implements ModelClient {

    /** Gemini models take images, audio, video and PDFs; files in Google's File API can be given as URLs. */
    public static final ModelCapabilities DEFAULT_CAPABILITIES = ModelCapabilities
            .of(Modality.IMAGE, Modality.AUDIO, Modality.VIDEO, Modality.DOCUMENT)
            .withMediaUrls(Modality.IMAGE, Modality.AUDIO, Modality.VIDEO, Modality.DOCUMENT);

    private final Client client;
    private final String model;
    private final ModelCapabilities capabilities;
    private final Integer requestTimeoutMillis;

    public GeminiModelClient(String apiKey, String model) {
        this(apiKey, model, HttpOptions.defaults());
    }

    public GeminiModelClient(String apiKey, String model, HttpOptions options) {
        this(apiKey, model, options, null);
    }

    /**
     * @param baseUrl another API root, such as a proxy that forwards to Gemini; null for Google's own
     */
    public GeminiModelClient(String apiKey, String model, HttpOptions options, String baseUrl) {
        this(GeminiClients.create(apiKey, options, baseUrl), model, DEFAULT_CAPABILITIES,
                (int) Math.min(Integer.MAX_VALUE, options.requestTimeout().toMillis()));
    }

    /** Uses an SDK client you configured yourself (for example for Vertex AI). */
    public GeminiModelClient(Client client, String model) {
        this(client, model, DEFAULT_CAPABILITIES, null);
    }

    private GeminiModelClient(Client client, String model, ModelCapabilities capabilities, Integer requestTimeoutMillis) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.model = Objects.requireNonNull(model, "model must not be null");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities must not be null");
        this.requestTimeoutMillis = requestTimeoutMillis;
    }

    /** A copy with different capabilities, for example for a text-only model. */
    public GeminiModelClient withCapabilities(ModelCapabilities capabilities) {
        return new GeminiModelClient(client, model, capabilities, requestTimeoutMillis);
    }

    @Override
    public ModelCapabilities capabilities() {
        return capabilities;
    }

    public String model() {
        return model;
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<ToolSchema> tools) {
        // A single reply is bounded by the request timeout; streamed replies are not, since long
        // answers keep arriving for longer than that.
        GenerateContentResponse response = client.models.generateContent(model, contents(messages, tools),
                config(messages, tools, true));
        Reply reply = new Reply();
        reply.add(response, null);
        if (response.candidates().map(List::isEmpty).orElse(true)) {
            throw new RuntimeException("Gemini returned no candidates: " + response.toJson());
        }
        return reply.toResponse();
    }

    @Override
    public ChatResponse streamChat(List<Message> messages, List<ToolSchema> tools, Consumer<String> onDelta) {
        Reply reply = new Reply();
        try (ResponseStream<GenerateContentResponse> stream =
                     client.models.generateContentStream(model, contents(messages, tools), config(messages, tools, false))) {
            for (GenerateContentResponse chunk : stream) reply.add(chunk, onDelta);
        }
        return reply.toResponse();
    }

    @Override
    public int countTokens(List<Message> messages) {
        // Google's API takes no separate system instruction when counting, so the system prompt is
        // counted as part of the conversation: the same text, so the same number of tokens.
        List<Content> contents = new ArrayList<>();
        systemInstruction(messages).ifPresent(system -> contents.add(system.toBuilder().role("user").build()));
        contents.addAll(contents(messages, List.of()));
        return client.models.countTokens(model, contents, CountTokensConfig.builder().build()).totalTokens().orElse(0);
    }

    /** What has arrived so far of one reply, from one response or many streamed chunks. */
    private static final class Reply {
        final StringBuilder text = new StringBuilder();
        final List<ToolCall> toolCalls = new ArrayList<>();
        FinishReason finish = FinishReason.UNKNOWN;
        ModelUsage usage = ModelUsage.empty();

        void add(GenerateContentResponse response, Consumer<String> onDelta) {
            response.usageMetadata().ifPresent(metadata -> usage = usage(metadata));
            List<Candidate> candidates = response.candidates().orElse(List.of());
            if (candidates.isEmpty()) return;
            Candidate first = candidates.get(0);
            first.finishReason().ifPresent(reason -> finish = finishReason(reason.toString(), false));
            for (Part part : first.content().flatMap(Content::parts).orElse(List.of())) {
                if (part.functionCall().isPresent()) {
                    toolCalls.add(toolCall(part));
                } else if (part.text().isPresent() && !part.thought().orElse(false)) {
                    String piece = part.text().get();
                    text.append(piece);
                    if (onDelta != null && !piece.isEmpty()) onDelta.accept(piece);
                }
            }
        }

        ChatResponse toResponse() {
            // The calls stay on the message: Gemini must see its own functionCall (with its
            // thoughtSignature) before the matching functionResponse on the next turn.
            Message message = new Message(Role.ASSISTANT, text.toString(), null, null, toolCalls);
            return new ChatResponse(message, toolCalls, toolCalls.isEmpty() ? finish : FinishReason.TOOL_CALLS, usage);
        }
    }

    private static ToolCall toolCall(Part part) {
        FunctionCall call = part.functionCall().orElseThrow();
        Map<String, Object> args = call.args().orElse(Map.of());
        String signature = part.thoughtSignature().map(bytes -> Base64.getEncoder().encodeToString(bytes)).orElse(null);
        return new ToolCall(call.id().orElseGet(() -> UUID.randomUUID().toString()), call.name().orElse(""),
                new JSONObject(args).toString(), signature);
    }

    List<Content> contents(List<Message> messages, List<ToolSchema> tools) {
        capabilities.check(messages, tools, "Gemini", model);
        // Gemini knows only "user" and "model" turns, which must alternate: tool results are "user"
        // turns, and consecutive messages with the same role are merged into one turn.
        List<Content> contents = new ArrayList<>();
        String role = null;
        List<Part> parts = null;
        for (Message message : messages) {
            if (message.getRole() == Role.SYSTEM) continue;
            String next = message.getRole() == Role.ASSISTANT ? "model" : "user";
            if (!next.equals(role)) {
                if (parts != null) contents.add(content(role, parts));
                role = next;
                parts = new ArrayList<>();
            }
            addParts(parts, message);
        }
        if (parts != null) contents.add(content(role, parts));
        return contents;
    }

    private static Content content(String role, List<Part> parts) {
        if (parts.isEmpty()) parts.add(Part.builder().text(" ").build());
        return Content.builder().role(role).parts(parts).build();
    }

    private static void addParts(List<Part> parts, Message message) {
        if (message.getRole() == Role.TOOL) {
            parts.add(Part.builder().functionResponse(FunctionResponse.builder()
                    .name(message.getToolCallName() != null ? message.getToolCallName() : "unknown")
                    .response(Map.of("result", message.getContent()))
                    .build()).build());
            return;
        }
        if (!message.getContent().isEmpty()) parts.add(Part.builder().text(message.getContent()).build());
        for (Media media : message.getMedia()) parts.add(mediaPart(media));
        if (message.getRole() == Role.ASSISTANT) {
            for (ToolCall call : message.getToolCalls()) {
                Part.Builder part = Part.builder().functionCall(FunctionCall.builder()
                        .name(call.getName())
                        .args(new JSONObject(call.getArgumentsJson()).toMap())
                        .build());
                if (call.getSignature() != null) part.thoughtSignature(signatureBytes(call.getSignature()));
                parts.add(part.build());
            }
        }
    }

    /** Signatures are stored as base64 text; older sessions may hold the URL-safe form. */
    static byte[] signatureBytes(String signature) {
        try {
            return Base64.getDecoder().decode(signature);
        } catch (IllegalArgumentException notStandard) {
            return Base64.getUrlDecoder().decode(signature);
        }
    }

    /** Bytes go inline; URLs (Google File API URIs, or other URLs Gemini can fetch) as file data. */
    static Part mediaPart(Media media) {
        if (media.hasData()) {
            return Part.builder().inlineData(Blob.builder().mimeType(media.mimeType()).data(media.data()).build()).build();
        }
        return Part.builder().fileData(FileData.builder().mimeType(media.mimeType()).fileUri(media.url()).build()).build();
    }

    private GenerateContentConfig config(List<Message> messages, List<ToolSchema> tools, boolean bounded) {
        GenerateContentConfig.Builder config = GenerateContentConfig.builder();
        systemInstruction(messages).ifPresent(config::systemInstruction);
        if (tools != null && !tools.isEmpty()) {
            List<FunctionDeclaration> declarations = new ArrayList<>();
            for (ToolSchema tool : tools) {
                FunctionDeclaration.Builder declaration = FunctionDeclaration.builder()
                        .name(tool.getName()).description(tool.getDescription());
                if (tool.getJsonSchema() != null && !tool.getJsonSchema().isBlank()) {
                    // Standard JSON Schema: the older "parameters" field only takes an OpenAPI subset and
                    // rejects keys such as additionalProperties, which tools from MCP servers often use.
                    declaration.parametersJsonSchema(new JSONObject(tool.getJsonSchema()).toMap());
                }
                declarations.add(declaration.build());
            }
            config.tools(List.of(Tool.builder().functionDeclarations(declarations).build()));
            config.toolConfig(ToolConfig.builder()
                    .functionCallingConfig(FunctionCallingConfig.builder().mode("AUTO").build()).build());
        }
        if (bounded && requestTimeoutMillis != null) {
            config.httpOptions(com.google.genai.types.HttpOptions.builder().timeout(requestTimeoutMillis).build());
        }
        return config.build();
    }

    private static Optional<Content> systemInstruction(List<Message> messages) {
        return messages.stream().filter(m -> m.getRole() == Role.SYSTEM).findFirst()
                .map(m -> Content.builder().parts(List.of(Part.builder().text(m.getContent()).build())).build());
    }

    static ModelUsage usage(GenerateContentResponseUsageMetadata metadata) {
        return new ModelUsage(metadata.promptTokenCount().orElse(0), metadata.candidatesTokenCount().orElse(0),
                metadata.totalTokenCount().orElse(0));
    }

    static FinishReason finishReason(String reason, boolean hasToolCalls) {
        if (hasToolCalls) return FinishReason.TOOL_CALLS;
        return switch (reason == null ? "" : reason.toUpperCase(Locale.ROOT)) {
            case "STOP" -> FinishReason.STOP;
            case "MAX_TOKENS", "RECITATION" -> FinishReason.LENGTH;
            case "SAFETY", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII" -> FinishReason.CONTENT_FILTER;
            case "MALFORMED_FUNCTION_CALL" -> FinishReason.TOOL_CALLS;
            default -> FinishReason.UNKNOWN;
        };
    }
}
