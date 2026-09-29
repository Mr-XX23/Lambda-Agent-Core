package ai.lambda.ai.client;

import ai.lambda.ai.core.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A client for OpenAI and every provider that speaks its Chat Completions protocol:
 *
 * <pre>
 * OpenAIModelClient.openAI(key, "gpt-5");
 * OpenAIModelClient.openRouter(key, "anthropic/claude-opus-5-5");
 * OpenAIModelClient.xai(key, "grok-4.7");
 * OpenAIModelClient.mistral(key, "mistral-medium-latest");
 * OpenAIModelClient.experientialLabs(key, "qwen3.8-27b");
 * OpenAIModelClient.ollama("gemma4");
 * </pre>
 *
 * For Perplexity use {@link ResponsesModelClient#perplexity}, and for Gemini {@link GoogleModelClient}.
 *
 * Messages may carry images, audio, video and documents ({@link Media}); what each provider and
 * model accepts is described by {@link #capabilities()} and checked before a request is sent.
 */
public class OpenAIModelClient implements ModelClient {

    private final OpenAICompatibleProvider provider;
    private final HttpClient httpClient;
    private final HttpOptions options;
    private final String apiKey;
    private final String model;
    private final ModelCapabilities capabilities;
    private final URI chatCompletions;

    public OpenAIModelClient(String apiKey, String model) {
        this(apiKey, model, HttpOptions.defaults());
    }

    public OpenAIModelClient(String apiKey, String model, HttpOptions options) {
        this(OpenAICompatibleProvider.OPENAI, apiKey, model, options);
    }

    public OpenAIModelClient(OpenAICompatibleProvider provider, String apiKey, String model, HttpOptions options) {
        this(provider, apiKey, model, options, null);
    }

    private OpenAIModelClient(OpenAICompatibleProvider provider, String apiKey, String model, HttpOptions options,
                              ModelCapabilities capabilities) {
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
        this.model = Objects.requireNonNull(model, "model must not be null");
        this.options = Objects.requireNonNull(options, "options must not be null");
        if (provider.requiresApiKey() && (apiKey == null || apiKey.isBlank())) {
            throw new IllegalArgumentException(provider.name() + " needs an API key");
        }
        this.apiKey = apiKey;
        this.capabilities = capabilities != null ? capabilities : provider.capabilities().apply(model);
        this.chatCompletions = URI.create(provider.baseUrl() + "/chat/completions");
        this.httpClient = HttpRetry.newClient(options);
    }

    public static OpenAIModelClient openAI(String apiKey, String model) {
        return new OpenAIModelClient(OpenAICompatibleProvider.OPENAI, apiKey, model, HttpOptions.defaults());
    }

    public static OpenAIModelClient openRouter(String apiKey, String model) {
        return new OpenAIModelClient(OpenAICompatibleProvider.OPENROUTER, apiKey, model, HttpOptions.defaults());
    }

    public static OpenAIModelClient experientialLabs(String apiKey, String model) {
        return new OpenAIModelClient(OpenAICompatibleProvider.EXPERIENTIAL_LABS, apiKey, model, HttpOptions.defaults());
    }

    public static OpenAIModelClient xai(String apiKey, String model) {
        return new OpenAIModelClient(OpenAICompatibleProvider.XAI, apiKey, model, HttpOptions.defaults());
    }

    public static OpenAIModelClient mistral(String apiKey, String model) {
        return new OpenAIModelClient(OpenAICompatibleProvider.MISTRAL, apiKey, model, HttpOptions.defaults());
    }

    /** A model served by Ollama on this machine. */
    public static OpenAIModelClient ollama(String model) {
        return new OpenAIModelClient(OpenAICompatibleProvider.OLLAMA, null, model, HttpOptions.defaults());
    }

    /** A model on Ollama's hosted service. */
    public static OpenAIModelClient ollamaCloud(String apiKey, String model) {
        return new OpenAIModelClient(OpenAICompatibleProvider.OLLAMA_CLOUD, apiKey, model, HttpOptions.defaults());
    }

    /** A model on Perplexity's Router API (private preview). */
    public static OpenAIModelClient perplexityRouter(String apiKey, String model) {
        return new OpenAIModelClient(OpenAICompatibleProvider.PERPLEXITY_ROUTER, apiKey, model, HttpOptions.defaults());
    }

    /** A copy with different capabilities, for models that accept less (or more) than the provider's defaults. */
    public OpenAIModelClient withCapabilities(ModelCapabilities capabilities) {
        return new OpenAIModelClient(provider, apiKey, model, options, Objects.requireNonNull(capabilities));
    }

    @Override
    public ModelCapabilities capabilities() {
        return capabilities;
    }

    public OpenAICompatibleProvider provider() {
        return provider;
    }

    public String model() {
        return model;
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<ToolSchema> tools) {
        HttpRequest request = buildRequest(buildRequestBody(messages, tools, false));

        HttpResponse<String> response = HttpRetry.send(
                httpClient, request, HttpResponse.BodyHandlers.ofString(), options, provider.name());

        if (response.statusCode() >= 400) {
            throw new RuntimeException(provider.name() + " error: " + response.statusCode() + " " + response.body());
        }

        JSONObject body = new JSONObject(response.body());
        throwIfError(body);
        JSONArray choices = body.getJSONArray("choices");
        if (choices.isEmpty()) {
            throw new RuntimeException(provider.name() + " returned no choices");
        }
        JSONObject first = choices.getJSONObject(0);
        JSONObject message = first.getJSONObject("message");
        String content = message.isNull("content") ? "" : message.optString("content", "");
        List<ToolCall> toolCalls = new ArrayList<>();
        JSONArray responseToolCalls = message.optJSONArray("tool_calls");
        if (responseToolCalls != null) {
            for (int i = 0; i < responseToolCalls.length(); i++) {
                JSONObject toolCall = responseToolCalls.getJSONObject(i);
                JSONObject function = toolCall.getJSONObject("function");
                toolCalls.add(new ToolCall(
                        toolCall.getString("id"),
                        function.getString("name"),
                        arguments(function.opt("arguments"))));
            }
        }

        Message assistantMessage = new Message(Role.ASSISTANT, content, null, null, toolCalls);
        return new ChatResponse(assistantMessage, toolCalls,
                toFinishReason(first.optString("finish_reason", "")), parseUsage(body));
    }

    @Override
    public ChatResponse streamChat(List<Message> messages, List<ToolSchema> tools, Consumer<String> onDelta) {
        HttpRequest request = buildRequest(buildRequestBody(messages, tools, true));
        HttpResponse<Stream<String>> response =
                HttpRetry.send(httpClient, request, linesOrErrorBody(), options, provider.name());

        StringBuilder text = new StringBuilder();
        Map<Integer, StreamToolCall> calls = new LinkedHashMap<>();
        FinishReason finishReason = FinishReason.UNKNOWN;
        ModelUsage usage = ModelUsage.empty();
        try (Stream<String> lines = response.body()) {
            if (response.statusCode() >= 400) {
                throw new RuntimeException(provider.name() + " streaming error: " + response.statusCode() + " "
                        + lines.collect(Collectors.joining("\n")));
            }
            for (String line : (Iterable<String>) lines::iterator) {
                // Other lines are blank separators or keep-alive comments (": ...").
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.isEmpty()) continue;
                if ("[DONE]".equals(data)) break;
                JSONObject chunk = new JSONObject(data);
                throwIfError(chunk);
                if (chunk.optJSONObject("usage") != null) usage = parseUsage(chunk);
                JSONArray choices = chunk.optJSONArray("choices");
                if (choices == null || choices.isEmpty()) continue;
                JSONObject choice = choices.getJSONObject(0);
                String reason = choice.optString("finish_reason", "");
                if (!reason.isEmpty() && !choice.isNull("finish_reason")) finishReason = toFinishReason(reason);
                JSONObject delta = choice.optJSONObject("delta");
                if (delta == null) continue;
                String piece = delta.isNull("content") ? "" : delta.optString("content", "");
                if (!piece.isEmpty()) {
                    text.append(piece);
                    if (onDelta != null) onDelta.accept(piece);
                }
                JSONArray toolDeltas = delta.optJSONArray("tool_calls");
                if (toolDeltas != null) {
                    for (int i = 0; i < toolDeltas.length(); i++) {
                        JSONObject item = toolDeltas.getJSONObject(i);
                        int index = item.optInt("index", i);
                        StreamToolCall call = calls.computeIfAbsent(index,
                                ignored -> new StreamToolCall(item.optString("id", ""), "", new StringBuilder()));
                        if (item.has("id") && !item.isNull("id")) call.id = item.getString("id");
                        JSONObject function = item.optJSONObject("function");
                        if (function != null) {
                            if (function.has("name") && !function.isNull("name")) call.name = function.getString("name");
                            Object args = function.opt("arguments");
                            // Most providers stream argument text; some send the whole object at once.
                            if (args instanceof JSONObject object) call.arguments.append(object);
                            else if (args instanceof String s) call.arguments.append(s);
                        }
                    }
                }
            }
        }
        List<ToolCall> toolCalls = calls.values().stream()
                .map(call -> new ToolCall(call.id, call.name,
                        call.arguments.length() == 0 ? "{}" : call.arguments.toString()))
                .toList();
        return new ChatResponse(new Message(Role.ASSISTANT, text.toString(), null, null, toolCalls),
                toolCalls, toolCalls.isEmpty() ? finishReason : FinishReason.TOOL_CALLS, usage);
    }

    private static String arguments(Object value) {
        if (value instanceof JSONObject object) return object.toString();
        if (value instanceof String s && !s.isBlank()) return s;
        return "{}";
    }

    private void throwIfError(JSONObject body) {
        JSONObject error = body.optJSONObject("error");
        if (error != null) {
            throw new RuntimeException(provider.name() + " error: " + error.optString("message", error.toString()));
        }
    }

    private HttpRequest buildRequest(JSONObject body) {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(chatCompletions)
                .timeout(options.requestTimeout())
                .header("Content-Type", "application/json");
        if (apiKey != null && !apiKey.isBlank()) request.header("Authorization", "Bearer " + apiKey);
        provider.headers().forEach(request::header);
        return request.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
    }

    // Streams lines on success; on an error status, reads the whole body so it can go in the exception.
    private static HttpResponse.BodyHandler<Stream<String>> linesOrErrorBody() {
        return info -> info.statusCode() < 400
                ? HttpResponse.BodySubscribers.ofLines(StandardCharsets.UTF_8)
                : HttpResponse.BodySubscribers.mapping(
                        HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8), Stream::of);
    }

    JSONObject buildRequestBody(List<Message> messages, List<ToolSchema> tools, boolean stream) {
        capabilities.check(messages, tools, provider.name(), model);
        JSONObject requestBody = new JSONObject().put("model", model).put("stream", stream);
        if (stream && provider.streamUsage()) {
            requestBody.put("stream_options", new JSONObject().put("include_usage", true));
        }
        JSONArray jsonMessages = new JSONArray();
        for (Message message : messages) {
            jsonMessages.put(toJson(message));
        }
        requestBody.put("messages", jsonMessages);
        if (tools != null && !tools.isEmpty()) {
            JSONArray declarations = new JSONArray();
            for (ToolSchema tool : tools) {
                declarations.put(new JSONObject().put("type", "function").put("function",
                        new JSONObject().put("name", tool.getName()).put("description", tool.getDescription())
                                .put("parameters", new JSONObject(tool.getJsonSchema()))));
            }
            requestBody.put("tools", declarations);
        }
        return requestBody;
    }

    private JSONObject toJson(Message message) {
        JSONObject json = new JSONObject().put("role", toOpenAiRole(message.getRole()));
        if (message.getRole() == Role.USER && !message.getMedia().isEmpty()) {
            JSONArray parts = new JSONArray();
            if (!message.getContent().isEmpty()) {
                parts.put(new JSONObject().put("type", "text").put("text", message.getContent()));
            }
            for (Media media : message.getMedia()) parts.put(mediaPart(media));
            json.put("content", parts);
        } else if (message.getRole() == Role.ASSISTANT && !message.getToolCalls().isEmpty()
                && message.getContent().isEmpty()) {
            json.put("content", JSONObject.NULL);
        } else {
            json.put("content", message.getContent());
        }
        if (message.getRole() == Role.TOOL) {
            if (message.getToolCallId() != null) json.put("tool_call_id", message.getToolCallId());
            if (provider.dialect() == OpenAICompatibleProvider.Dialect.MISTRAL && message.getToolCallName() != null) {
                json.put("name", message.getToolCallName());
            }
        }
        if (message.getRole() == Role.ASSISTANT && !message.getToolCalls().isEmpty()) {
            JSONArray toolCalls = new JSONArray();
            for (ToolCall call : message.getToolCalls()) {
                toolCalls.put(new JSONObject().put("id", call.getId()).put("type", "function")
                        .put("function", new JSONObject().put("name", call.getName())
                                .put("arguments", call.getArgumentsJson())));
            }
            json.put("tool_calls", toolCalls);
        }
        return json;
    }

    /** One media item as a content part, in this provider's dialect. */
    JSONObject mediaPart(Media media) {
        OpenAICompatibleProvider.Dialect dialect = provider.dialect();
        String mime = media.mimeType();
        switch (media.modality()) {
            case IMAGE -> {
                if (dialect == OpenAICompatibleProvider.Dialect.MISTRAL) {
                    return new JSONObject().put("type", "image_url").put("image_url", media.dataUrl());
                }
                return new JSONObject().put("type", "image_url").put("image_url", new JSONObject().put("url", media.dataUrl()));
            }
            case AUDIO -> {
                if (dialect == OpenAICompatibleProvider.Dialect.MISTRAL) {
                    // Mistral takes the audio as one string: base64 bytes, or a URL.
                    return new JSONObject().put("type", "input_audio")
                            .put("input_audio", media.hasData() ? media.base64() : media.url());
                }
                return new JSONObject().put("type", "input_audio").put("input_audio",
                        new JSONObject().put("data", requireBytes(media).base64()).put("format", audioFormat(mime)));
            }
            case VIDEO -> {
                if (dialect != OpenAICompatibleProvider.Dialect.OPENROUTER) throw unsupported(media);
                return new JSONObject().put("type", "video_url").put("video_url", new JSONObject().put("url", media.dataUrl()));
            }
            case DOCUMENT -> {
                if (mime.startsWith("text/") && media.hasData()) {
                    // Plain-text documents work everywhere as text.
                    String name = media.name() != null ? media.name() : "document";
                    return new JSONObject().put("type", "text")
                            .put("text", "[" + name + "]\n" + new String(media.data(), StandardCharsets.UTF_8));
                }
                if (!mime.equals("application/pdf")) throw unsupported(media);
                if (dialect == OpenAICompatibleProvider.Dialect.MISTRAL) {
                    return new JSONObject().put("type", "document_url").put("document_url", media.dataUrl());
                }
                String fileName = media.name() != null ? media.name() : "document.pdf";
                return new JSONObject().put("type", "file").put("file",
                        new JSONObject().put("filename", fileName).put("file_data", media.dataUrl()));
            }
            default -> throw unsupported(media);
        }
    }

    private String audioFormat(String mime) {
        String format = switch (mime) {
            case "audio/wav", "audio/x-wav", "audio/wave" -> "wav";
            case "audio/mpeg", "audio/mp3" -> "mp3";
            case "audio/aiff" -> "aiff";
            case "audio/aac" -> "aac";
            case "audio/ogg" -> "ogg";
            case "audio/flac" -> "flac";
            case "audio/mp4", "audio/m4a" -> "m4a";
            default -> null;
        };
        boolean wavOrMp3 = "wav".equals(format) || "mp3".equals(format);
        if (format == null || (provider.dialect() == OpenAICompatibleProvider.Dialect.OPENAI && !wavOrMp3)) {
            throw new UnsupportedMediaException(provider.name() + " does not accept " + mime + " audio"
                    + (provider.dialect() == OpenAICompatibleProvider.Dialect.OPENAI ? "; use WAV or MP3" : ""));
        }
        return format;
    }

    private Media requireBytes(Media media) {
        if (!media.hasData()) {
            throw new UnsupportedMediaException(provider.name() + " needs " + media.mimeType()
                    + " as bytes, not a URL (" + media.url() + ")");
        }
        return media;
    }

    private UnsupportedMediaException unsupported(Media media) {
        return new UnsupportedMediaException(provider.name() + " model '" + model + "' cannot take " + media.mimeType());
    }

    static ModelUsage parseUsage(JSONObject body) {
        JSONObject usage = body.optJSONObject("usage");
        if (usage == null) return ModelUsage.empty();
        return new ModelUsage(usage.optLong("prompt_tokens", 0),
                usage.optLong("completion_tokens", 0), usage.optLong("total_tokens", 0));
    }

    static FinishReason toFinishReason(String reason) {
        return switch (reason == null ? "" : reason.toLowerCase(Locale.ROOT)) {
            case "stop" -> FinishReason.STOP;
            case "tool_calls", "function_call" -> FinishReason.TOOL_CALLS;
            case "length", "model_length" -> FinishReason.LENGTH;
            case "content_filter" -> FinishReason.CONTENT_FILTER;
            default -> FinishReason.UNKNOWN;
        };
    }

    private static final class StreamToolCall {
        private String id;
        private String name;
        private final StringBuilder arguments;

        private StreamToolCall(String id, String name, StringBuilder arguments) {
            this.id = id;
            this.name = name;
            this.arguments = arguments;
        }
    }

    private static String toOpenAiRole(Role role) {
        return switch (role) {
            case SYSTEM -> "system";
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case TOOL -> "tool";
        };
    }

}
