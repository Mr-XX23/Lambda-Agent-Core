package ai.lambda.ai.anthropic;

import ai.lambda.ai.core.ChatResponse;
import ai.lambda.ai.core.FinishReason;
import ai.lambda.ai.core.Media;
import ai.lambda.ai.core.Message;
import ai.lambda.ai.core.Modality;
import ai.lambda.ai.core.ModelCapabilities;
import ai.lambda.ai.core.ModelClient;
import ai.lambda.ai.core.ModelUsage;
import ai.lambda.ai.core.ProviderState;
import ai.lambda.ai.core.Role;
import ai.lambda.ai.core.ToolCall;
import ai.lambda.ai.core.ToolSchema;
import ai.lambda.ai.core.UnsupportedMediaException;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.helpers.BetaMessageAccumulator;
import com.anthropic.models.beta.messages.BetaBase64ImageSource;
import com.anthropic.models.beta.messages.BetaContentBlock;
import com.anthropic.models.beta.messages.BetaContentBlockParam;
import com.anthropic.models.beta.messages.BetaImageBlockParam;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaMessageParam;
import com.anthropic.models.beta.messages.BetaRawMessageStreamEvent;
import com.anthropic.models.beta.messages.BetaRequestDocumentBlock;
import com.anthropic.models.beta.messages.BetaStopReason;
import com.anthropic.models.beta.messages.BetaTool;
import com.anthropic.models.beta.messages.BetaToolResultBlockParam;
import com.anthropic.models.beta.messages.BetaToolUseBlockParam;
import com.anthropic.models.beta.messages.MessageCountTokensParams;
import com.anthropic.models.beta.messages.MessageCreateParams;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Claude models, through the official Anthropic Java SDK.
 *
 * <pre>
 * ModelClient claude = new AnthropicModelClient(System.getenv("ANTHROPIC_API_KEY"));   // claude-opus-5-5
 * ModelClient sonnet = new AnthropicModelClient(key, "claude-sonnet-5-5");
 * </pre>
 *
 * <ul>
 *   <li><b>Media:</b> images (JPEG, PNG, GIF, WebP) and PDFs, as files or URLs; plain-text documents.</li>
 *   <li><b>Thinking:</b> each assistant turn is stored with its signed thinking blocks and sent back
 *       unchanged, as Claude requires. Where the model supports it, requests ask the API to drop
 *       thinking blocks whose conversation changed (for example after history trimming) instead of
 *       failing; see {@link #withMismatchedThinkingDropped(boolean)}.</li>
 *   <li><b>Refusals:</b> on the models that support it, a declined request is retried on a fallback
 *       model chosen by the API ({@code fallbacks: "default"}); see {@link #withRefusalFallbacks(boolean)}.
 *       A refusal that stands ends with {@link FinishReason#CONTENT_FILTER}.</li>
 * </ul>
 */
public final class AnthropicModelClient implements ModelClient {

    /** The {@link ProviderState} name used for Claude's assistant turns. */
    public static final String PROVIDER = "anthropic";
    public static final String DEFAULT_MODEL = "claude-opus-5-5";

    static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";
    static final String THINKING_BINDING_BETA = "thinking-binding-controls-2026-08-01";

    /** Models that take server-side refusal fallbacks ({@code fallbacks: "default"}). */
    private static final Set<String> FALLBACK_MODELS = Set.of("claude-fable-5-1", "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5");
    /** Older models that do not take {@code thinking: {type: "adaptive"}}. */
    private static final Pattern NO_ADAPTIVE_THINKING = Pattern.compile(
            "claude-(3|haiku|(opus|sonnet)-4(-[0-5])?($|-\\d{8})).*");
    private static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/gif", "image/webp");

    private final AnthropicClient client;
    private final String model;
    private final long maxTokens;
    private final String effort;
    private final boolean refusalFallbacks;
    private final boolean dropMismatchedThinking;
    private final ModelCapabilities capabilities;

    /** {@value #DEFAULT_MODEL} with this API key. */
    public AnthropicModelClient(String apiKey) {
        this(apiKey, DEFAULT_MODEL);
    }

    public AnthropicModelClient(String apiKey, String model) {
        this(AnthropicOkHttpClient.builder().apiKey(Objects.requireNonNull(apiKey, "apiKey must not be null")).build(), model);
    }

    /** Uses an SDK client you configured (credentials, base URL, timeouts, retries). */
    public AnthropicModelClient(AnthropicClient client, String model) {
        this(client, model, 64_000, null, FALLBACK_MODELS.contains(model), supportsAdaptiveThinking(model),
                ModelCapabilities.of(Modality.IMAGE, Modality.DOCUMENT).withMediaUrls(Modality.IMAGE, Modality.DOCUMENT));
    }

    private AnthropicModelClient(AnthropicClient client, String model, long maxTokens, String effort, boolean refusalFallbacks,
                                 boolean dropMismatchedThinking, ModelCapabilities capabilities) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.model = Objects.requireNonNull(model, "model must not be null");
        if (maxTokens < 1) throw new IllegalArgumentException("maxTokens must be at least 1");
        this.maxTokens = maxTokens;
        this.effort = effort;
        this.refusalFallbacks = refusalFallbacks;
        this.dropMismatchedThinking = dropMismatchedThinking;
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities must not be null");
    }

    /**
     * Credentials from the environment ({@code ANTHROPIC_API_KEY}, {@code ANTHROPIC_AUTH_TOKEN},
     * or a profile from {@code ant auth login}).
     */
    public static AnthropicModelClient fromEnv(String model) {
        return new AnthropicModelClient(AnthropicOkHttpClient.fromEnv(), model);
    }

    static boolean supportsAdaptiveThinking(String model) {
        return !NO_ADAPTIVE_THINKING.matcher(model).matches();
    }

    /** The most tokens one reply may use (default 64,000; requests stream, so large values are fine). */
    public AnthropicModelClient withMaxTokens(long maxTokens) {
        return new AnthropicModelClient(client, model, maxTokens, effort, refusalFallbacks, dropMismatchedThinking, capabilities);
    }

    /**
     * How much effort Claude spends: {@code low}, {@code medium}, {@code high}, {@code xhigh} or
     * {@code max}. Unset, the model's default applies ({@code medium} on Claude Opus 5.5).
     */
    public AnthropicModelClient withEffort(String effort) {
        return new AnthropicModelClient(client, model, maxTokens, effort, refusalFallbacks, dropMismatchedThinking, capabilities);
    }

    /** Whether a refused request is retried on a fallback model chosen by the API (on by default where supported). */
    public AnthropicModelClient withRefusalFallbacks(boolean enabled) {
        return new AnthropicModelClient(client, model, maxTokens, effort, enabled, dropMismatchedThinking, capabilities);
    }

    /**
     * Whether thinking blocks that no longer match their conversation (after history trimming, or
     * a change in tools) are dropped by the API ({@code true}, the default where supported) or make
     * the request fail ({@code false}, useful in tests to catch history edits).
     */
    public AnthropicModelClient withMismatchedThinkingDropped(boolean drop) {
        return new AnthropicModelClient(client, model, maxTokens, effort, refusalFallbacks, drop, capabilities);
    }

    public AnthropicModelClient withCapabilities(ModelCapabilities capabilities) {
        return new AnthropicModelClient(client, model, maxTokens, effort, refusalFallbacks, dropMismatchedThinking, capabilities);
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
        return toChatResponse(client.beta().messages().create(params(messages, tools, Math.min(maxTokens, 16_000))));
    }

    @Override
    public ChatResponse streamChat(List<Message> messages, List<ToolSchema> tools, Consumer<String> onDelta) {
        BetaMessageAccumulator accumulator = BetaMessageAccumulator.create();
        try (StreamResponse<BetaRawMessageStreamEvent> stream =
                     client.beta().messages().createStreaming(params(messages, tools, maxTokens))) {
            stream.stream().forEach(event -> {
                accumulator.accumulate(event);
                if (onDelta != null) {
                    event.contentBlockDelta()
                            .flatMap(delta -> delta.delta().text())
                            .ifPresent(text -> onDelta.accept(text.text()));
                }
            });
        }
        return toChatResponse(accumulator.message());
    }

    @Override
    public int countTokens(List<Message> messages) {
        Converted converted = convert(messages);
        MessageCountTokensParams.Builder params = MessageCountTokensParams.builder().model(model).messages(converted.messages());
        if (converted.system() != null) params.system(converted.system());
        return (int) client.beta().messages().countTokens(params.build()).inputTokens();
    }

    // --- Request ---

    MessageCreateParams params(List<Message> messages, List<ToolSchema> tools, long maxTokens) {
        capabilities.check(messages, tools, "Claude", model);
        Converted converted = convert(messages);
        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxTokens)
                .messages(converted.messages());
        if (converted.system() != null) params.system(converted.system());
        if (tools != null) for (ToolSchema tool : tools) params.addTool(tool(tool));
        if (dropMismatchedThinking) {
            params.addBeta(THINKING_BINDING_BETA);
            params.putAdditionalBodyProperty("thinking", JsonValue.from(Map.of("type", "adaptive",
                    "block_binding", Map.of("prefix_mismatch_behavior", "drop_block"))));
        }
        if (refusalFallbacks) {
            params.addBeta(FALLBACK_BETA);
            params.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }
        if (effort != null) params.putAdditionalBodyProperty("output_config", JsonValue.from(Map.of("effort", effort)));
        return params.build();
    }

    private record Converted(String system, List<BetaMessageParam> messages) {
    }

    private Converted convert(List<Message> messages) {
        String system = null;
        List<BetaMessageParam> out = new ArrayList<>();
        List<BetaContentBlockParam> pendingUser = new ArrayList<>();
        for (Message message : messages) {
            switch (message.getRole()) {
                case SYSTEM -> {
                    if (system == null) system = message.getContent();
                    else if (!message.getContent().isEmpty()) pendingUser.add(BetaContentBlockParam.ofText(message.getContent()));
                }
                case USER -> {
                    // Documents and images first, then the question, as Anthropic recommends.
                    for (Media media : message.getMedia()) pendingUser.add(mediaBlock(media));
                    if (!message.getContent().isEmpty()) pendingUser.add(BetaContentBlockParam.ofText(message.getContent()));
                }
                case TOOL -> pendingUser.add(BetaContentBlockParam.ofToolResult(BetaToolResultBlockParam.builder()
                        .toolUseId(message.getToolCallId())
                        .content(message.getContent().isEmpty() ? "(no output)" : message.getContent())
                        .build()));
                case ASSISTANT -> {
                    flush(out, pendingUser);
                    out.add(assistant(message));
                }
            }
        }
        flush(out, pendingUser);
        return new Converted(system, out);
    }

    private static void flush(List<BetaMessageParam> out, List<BetaContentBlockParam> blocks) {
        if (blocks.isEmpty()) return;
        out.add(BetaMessageParam.builder().role(BetaMessageParam.Role.USER).contentOfBetaContentBlockParams(List.copyOf(blocks)).build());
        blocks.clear();
    }

    private BetaMessageParam assistant(Message message) {
        ProviderState state = message.getProviderState();
        if (state != null && PROVIDER.equals(state.provider())) {
            // Replay Claude's own turn exactly, thinking blocks and signatures included.
            try {
                return ObjectMappers.jsonMapper().readValue(state.json(), BetaMessageParam.class);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Stored Claude turn cannot be read back", e);
            }
        }
        List<BetaContentBlockParam> blocks = new ArrayList<>();
        if (!message.getContent().isEmpty()) blocks.add(BetaContentBlockParam.ofText(message.getContent()));
        for (ToolCall call : message.getToolCalls()) {
            blocks.add(BetaContentBlockParam.ofToolUse(BetaToolUseBlockParam.builder()
                    .id(call.getId())
                    .name(call.getName())
                    .input(BetaToolUseBlockParam.Input.builder()
                            .additionalProperties(toJsonValues(new JSONObject(call.getArgumentsJson()).toMap()))
                            .build())
                    .build()));
        }
        if (blocks.isEmpty()) blocks.add(BetaContentBlockParam.ofText("(no reply)"));
        return BetaMessageParam.builder().role(BetaMessageParam.Role.ASSISTANT).contentOfBetaContentBlockParams(blocks).build();
    }

    private static Map<String, JsonValue> toJsonValues(Map<String, Object> map) {
        Map<String, JsonValue> values = new java.util.LinkedHashMap<>();
        map.forEach((key, value) -> values.put(key, JsonValue.from(value)));
        return values;
    }

    private static BetaContentBlockParam mediaBlock(Media media) {
        if (media.modality() == Modality.IMAGE) {
            if (!media.hasData()) return BetaContentBlockParam.ofImage(BetaImageBlockParam.builder().urlSource(media.url()).build());
            if (!IMAGE_TYPES.contains(media.mimeType())) {
                throw new UnsupportedMediaException("Claude accepts JPEG, PNG, GIF and WebP images, not " + media.mimeType());
            }
            return BetaContentBlockParam.ofImage(BetaImageBlockParam.builder()
                    .source(BetaBase64ImageSource.builder()
                            .data(media.base64())
                            .mediaType(BetaBase64ImageSource.MediaType.of(media.mimeType()))
                            .build())
                    .build());
        }
        if (media.modality() == Modality.DOCUMENT) {
            BetaRequestDocumentBlock.Builder document = BetaRequestDocumentBlock.builder();
            if (media.mimeType().startsWith("text/")) {
                if (!media.hasData()) throw new UnsupportedMediaException("Claude needs text documents as bytes, not a URL");
                document.textSource(new String(media.data(), StandardCharsets.UTF_8));
            } else if (media.mimeType().equals("application/pdf")) {
                if (media.hasData()) document.base64Source(media.base64());
                else document.urlSource(media.url());
            } else {
                throw new UnsupportedMediaException("Claude accepts PDF and plain-text documents, not " + media.mimeType());
            }
            if (media.name() != null) document.title(media.name());
            return BetaContentBlockParam.ofDocument(document.build());
        }
        throw new UnsupportedMediaException("Claude does not accept " + media.mimeType());
    }

    private static BetaTool tool(ToolSchema schema) {
        JSONObject json = new JSONObject(schema.getJsonSchema());
        BetaTool.InputSchema.Builder input = BetaTool.InputSchema.builder();
        JSONObject properties = json.optJSONObject("properties");
        if (properties != null) {
            BetaTool.InputSchema.Properties.Builder props = BetaTool.InputSchema.Properties.builder();
            toJsonValues(properties.toMap()).forEach(props::putAdditionalProperty);
            input.properties(props.build());
        }
        if (json.optJSONArray("required") != null) {
            List<String> required = new ArrayList<>();
            json.getJSONArray("required").forEach(r -> required.add(r.toString()));
            input.required(required);
        }
        for (String key : json.keySet()) {
            if (!key.equals("type") && !key.equals("properties") && !key.equals("required")) {
                input.putAdditionalProperty(key, JsonValue.from(json.toMap().get(key)));
            }
        }
        return BetaTool.builder().name(schema.getName()).description(schema.getDescription()).inputSchema(input.build()).build();
    }

    // --- Response ---

    ChatResponse toChatResponse(BetaMessage message) {
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        for (BetaContentBlock block : message.content()) {
            block.text().ifPresent(t -> text.append(t.text()));
            block.toolUse().ifPresent(use -> calls.add(new ToolCall(use.id(), use.name(), json(use._input()))));
        }
        BetaStopReason stop = message.stopReason().orElse(null);
        FinishReason reason;
        if (!calls.isEmpty()) reason = FinishReason.TOOL_CALLS;
        else if (BetaStopReason.END_TURN.equals(stop) || BetaStopReason.STOP_SEQUENCE.equals(stop)) reason = FinishReason.STOP;
        else if (BetaStopReason.MAX_TOKENS.equals(stop) || BetaStopReason.MODEL_CONTEXT_WINDOW_EXCEEDED.equals(stop)) reason = FinishReason.LENGTH;
        else if (BetaStopReason.REFUSAL.equals(stop)) reason = FinishReason.CONTENT_FILTER;
        else reason = FinishReason.UNKNOWN;

        if (reason == FinishReason.CONTENT_FILTER && text.isEmpty()) {
            String details = message.stopDetails()
                    .map(d -> d.category().map(c -> " (" + c + ")").orElse("") + d.explanation().map(e -> ": " + e).orElse(""))
                    .orElse("");
            text.append("[Claude declined this request").append(details).append("]");
        }

        Message assistant = new Message(Role.ASSISTANT, text.toString(), null, null, calls)
                .withProviderState(new ProviderState(PROVIDER, json(message.toParam())));
        ModelUsage usage = new ModelUsage(message.usage().inputTokens(), message.usage().outputTokens(),
                message.usage().inputTokens() + message.usage().outputTokens());
        return new ChatResponse(assistant, calls, reason, usage);
    }

    private static String json(Object value) {
        try {
            return ObjectMappers.jsonMapper().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize Claude response", e);
        }
    }
}
