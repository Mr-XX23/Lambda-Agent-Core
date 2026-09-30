package ai.lambda.ai.openai;

import ai.lambda.ai.client.HttpOptions;
import ai.lambda.ai.client.OpenAICompatibleProvider;
import ai.lambda.ai.core.ChatResponse;
import ai.lambda.ai.core.FinishReason;
import ai.lambda.ai.core.Media;
import ai.lambda.ai.core.Message;
import ai.lambda.ai.core.ModelCapabilities;
import ai.lambda.ai.core.ModelClient;
import ai.lambda.ai.core.ModelUsage;
import ai.lambda.ai.core.Role;
import ai.lambda.ai.core.ToolCall;
import ai.lambda.ai.core.ToolSchema;
import ai.lambda.ai.core.UnsupportedMediaException;
import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.core.http.StreamResponse;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionContentPart;
import com.openai.models.chat.completions.ChatCompletionContentPartImage;
import com.openai.models.chat.completions.ChatCompletionContentPartInputAudio;
import com.openai.models.chat.completions.ChatCompletionContentPartText;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionFunctionTool;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import com.openai.models.chat.completions.ChatCompletionStreamOptions;
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
import com.openai.models.completions.CompletionUsage;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * OpenAI models (GPT) through OpenAI's official Java SDK.
 *
 * <pre>
 * ModelClient gpt = new OpenAIModelClient(System.getenv("OPENAI_API_KEY"), "gpt-5");
 * ModelClient same = Models.create("openai:gpt-5");            // key from OPENAI_API_KEY
 * </pre>
 *
 * Messages may carry images (files or URLs), PDFs (files) and, on the audio models, WAV or MP3
 * audio; {@link #capabilities()} describes this and requests are checked before they are sent.
 * For other servers that speak OpenAI's protocol, use {@code OpenAICompatibleModelClient} in
 * {@code lambda-ai-core}.
 */
public final class OpenAIModelClient implements ModelClient {

    private static final String PROVIDER = "OpenAI";

    private final OpenAIClient client;
    private final String model;
    private final ModelCapabilities capabilities;

    public OpenAIModelClient(String apiKey, String model) {
        this(apiKey, model, HttpOptions.defaults());
    }

    public OpenAIModelClient(String apiKey, String model, HttpOptions options) {
        this(apiKey, model, options, null);
    }

    /**
     * @param baseUrl another API root, such as a proxy that forwards to OpenAI; null for OpenAI's own
     */
    public OpenAIModelClient(String apiKey, String model, HttpOptions options, String baseUrl) {
        this(OpenAIClients.create(apiKey, options, baseUrl), model);
    }

    /** Uses an SDK client you configured yourself (for example with a proxy or an organization). */
    public OpenAIModelClient(OpenAIClient client, String model) {
        this(client, model, null);
    }

    private OpenAIModelClient(OpenAIClient client, String model, ModelCapabilities capabilities) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.model = Objects.requireNonNull(model, "model must not be null");
        this.capabilities = capabilities != null ? capabilities
                : OpenAICompatibleProvider.OPENAI.capabilities().apply(model);
    }

    /** A copy with different capabilities, for models that accept less (or more) than assumed. */
    public OpenAIModelClient withCapabilities(ModelCapabilities capabilities) {
        return new OpenAIModelClient(client, model, Objects.requireNonNull(capabilities));
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
        ChatCompletion completion = client.chat().completions().create(params(messages, tools, false));
        if (completion.choices().isEmpty()) throw new RuntimeException("OpenAI returned no choices");
        ChatCompletion.Choice choice = completion.choices().get(0);
        List<ToolCall> toolCalls = new ArrayList<>();
        for (ChatCompletionMessageToolCall call : choice.message().toolCalls().orElse(List.of())) {
            call.function().ifPresent(function -> toolCalls.add(new ToolCall(function.id(),
                    function.function().name(), arguments(function.function().arguments()))));
        }
        String text = choice.message().content().orElse("");
        return new ChatResponse(new Message(Role.ASSISTANT, text, null, null, toolCalls), toolCalls,
                finishReason(choice.finishReason().asString()), usage(completion.usage()));
    }

    @Override
    public ChatResponse streamChat(List<Message> messages, List<ToolSchema> tools, Consumer<String> onDelta) {
        StringBuilder text = new StringBuilder();
        Map<Long, PartialCall> calls = new LinkedHashMap<>();
        FinishReason finish = FinishReason.UNKNOWN;
        ModelUsage usage = ModelUsage.empty();
        try (StreamResponse<ChatCompletionChunk> stream =
                     client.chat().completions().createStreaming(params(messages, tools, true))) {
            for (ChatCompletionChunk chunk : (Iterable<ChatCompletionChunk>) stream.stream()::iterator) {
                if (chunk.usage().isPresent()) usage = usage(chunk.usage());
                if (chunk.choices().isEmpty()) continue;
                ChatCompletionChunk.Choice choice = chunk.choices().get(0);
                if (choice.finishReason().isPresent()) finish = finishReason(choice.finishReason().get().asString());
                Optional<String> piece = choice.delta().content();
                if (piece.isPresent() && !piece.get().isEmpty()) {
                    text.append(piece.get());
                    if (onDelta != null) onDelta.accept(piece.get());
                }
                for (ChatCompletionChunk.Choice.Delta.ToolCall delta : choice.delta().toolCalls().orElse(List.of())) {
                    PartialCall call = calls.computeIfAbsent(delta.index(), index -> new PartialCall());
                    delta.id().ifPresent(id -> call.id = id);
                    delta.function().ifPresent(function -> {
                        function.name().ifPresent(name -> call.name = name);
                        function.arguments().ifPresent(call.arguments::append);
                    });
                }
            }
        }
        List<ToolCall> toolCalls = calls.values().stream()
                .map(call -> new ToolCall(call.id, call.name, arguments(call.arguments.toString())))
                .toList();
        return new ChatResponse(new Message(Role.ASSISTANT, text.toString(), null, null, toolCalls), toolCalls,
                toolCalls.isEmpty() ? finish : FinishReason.TOOL_CALLS, usage);
    }

    private static final class PartialCall {
        private String id = "";
        private String name = "";
        private final StringBuilder arguments = new StringBuilder();
    }

    ChatCompletionCreateParams params(List<Message> messages, List<ToolSchema> tools, boolean stream) {
        capabilities.check(messages, tools, PROVIDER, model);
        ChatCompletionCreateParams.Builder params = ChatCompletionCreateParams.builder().model(model);
        for (Message message : messages) params.addMessage(toParam(message));
        if (tools != null) {
            for (ToolSchema tool : tools) {
                params.addTool(ChatCompletionFunctionTool.builder().function(FunctionDefinition.builder()
                        .name(tool.getName())
                        .description(tool.getDescription())
                        .parameters(parameters(tool.getJsonSchema()))
                        .build()).build());
            }
        }
        if (stream) params.streamOptions(ChatCompletionStreamOptions.builder().includeUsage(true).build());
        return params.build();
    }

    private static FunctionParameters parameters(String jsonSchema) {
        FunctionParameters.Builder parameters = FunctionParameters.builder();
        new JSONObject(jsonSchema).toMap().forEach((key, value) -> parameters.putAdditionalProperty(key, JsonValue.from(value)));
        return parameters.build();
    }

    private ChatCompletionMessageParam toParam(Message message) {
        return switch (message.getRole()) {
            case SYSTEM -> ChatCompletionMessageParam.ofSystem(
                    ChatCompletionSystemMessageParam.builder().content(message.getContent()).build());
            case USER -> ChatCompletionMessageParam.ofUser(user(message));
            case ASSISTANT -> {
                ChatCompletionAssistantMessageParam.Builder assistant = ChatCompletionAssistantMessageParam.builder();
                if (!message.getContent().isEmpty() || message.getToolCalls().isEmpty()) {
                    assistant.content(message.getContent());
                }
                for (ToolCall call : message.getToolCalls()) {
                    assistant.addToolCall(ChatCompletionMessageFunctionToolCall.builder()
                            .id(call.getId())
                            .function(ChatCompletionMessageFunctionToolCall.Function.builder()
                                    .name(call.getName()).arguments(call.getArgumentsJson()).build())
                            .build());
                }
                yield ChatCompletionMessageParam.ofAssistant(assistant.build());
            }
            case TOOL -> ChatCompletionMessageParam.ofTool(ChatCompletionToolMessageParam.builder()
                    .toolCallId(message.getToolCallId() == null ? "" : message.getToolCallId())
                    .content(message.getContent())
                    .build());
        };
    }

    private ChatCompletionUserMessageParam user(Message message) {
        if (message.getMedia().isEmpty()) {
            return ChatCompletionUserMessageParam.builder().content(message.getContent()).build();
        }
        List<ChatCompletionContentPart> parts = new ArrayList<>();
        if (!message.getContent().isEmpty()) parts.add(text(message.getContent()));
        for (Media media : message.getMedia()) parts.add(part(media));
        return ChatCompletionUserMessageParam.builder().contentOfArrayOfContentParts(parts).build();
    }

    private static ChatCompletionContentPart text(String text) {
        return ChatCompletionContentPart.ofText(ChatCompletionContentPartText.builder().text(text).build());
    }

    /** One media item as a content part. */
    ChatCompletionContentPart part(Media media) {
        String mime = media.mimeType();
        return switch (media.modality()) {
            case IMAGE -> ChatCompletionContentPart.ofImageUrl(ChatCompletionContentPartImage.builder()
                    .imageUrl(ChatCompletionContentPartImage.ImageUrl.builder().url(media.dataUrl()).build())
                    .build());
            case AUDIO -> {
                if (!media.hasData()) {
                    throw new UnsupportedMediaException("OpenAI needs " + mime + " as bytes, not a URL (" + media.url() + ")");
                }
                ChatCompletionContentPartInputAudio.InputAudio.Format format = switch (mime) {
                    case "audio/wav", "audio/x-wav", "audio/wave" -> ChatCompletionContentPartInputAudio.InputAudio.Format.WAV;
                    case "audio/mpeg", "audio/mp3" -> ChatCompletionContentPartInputAudio.InputAudio.Format.MP3;
                    default -> throw new UnsupportedMediaException("OpenAI does not accept " + mime + " audio; use WAV or MP3");
                };
                yield ChatCompletionContentPart.ofInputAudio(ChatCompletionContentPartInputAudio.builder()
                        .inputAudio(ChatCompletionContentPartInputAudio.InputAudio.builder()
                                .data(media.base64()).format(format).build())
                        .build());
            }
            case DOCUMENT -> {
                if (mime.startsWith("text/") && media.hasData()) {
                    // Plain-text documents are sent as text.
                    String name = media.name() != null ? media.name() : "document";
                    yield text("[" + name + "]\n" + new String(media.data(), StandardCharsets.UTF_8));
                }
                if (!mime.equals("application/pdf")) throw unsupported(media);
                yield ChatCompletionContentPart.ofFile(ChatCompletionContentPart.File.builder()
                        .file(ChatCompletionContentPart.File.FileObject.builder()
                                .filename(media.name() != null ? media.name() : "document.pdf")
                                .fileData(media.dataUrl())
                                .build())
                        .build());
            }
            default -> throw unsupported(media);
        };
    }

    private UnsupportedMediaException unsupported(Media media) {
        return new UnsupportedMediaException("OpenAI model '" + model + "' cannot take " + media.mimeType());
    }

    private static String arguments(String value) {
        return value == null || value.isBlank() ? "{}" : value;
    }

    private static ModelUsage usage(Optional<CompletionUsage> usage) {
        return usage.map(u -> new ModelUsage(u.promptTokens(), u.completionTokens(), u.totalTokens()))
                .orElse(ModelUsage.empty());
    }

    static FinishReason finishReason(String reason) {
        return switch (reason == null ? "" : reason.toLowerCase(Locale.ROOT)) {
            case "stop" -> FinishReason.STOP;
            case "tool_calls", "function_call" -> FinishReason.TOOL_CALLS;
            case "length" -> FinishReason.LENGTH;
            case "content_filter" -> FinishReason.CONTENT_FILTER;
            default -> FinishReason.UNKNOWN;
        };
    }
}
