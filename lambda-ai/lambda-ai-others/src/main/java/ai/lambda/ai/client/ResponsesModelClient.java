package ai.lambda.ai.client;

import ai.lambda.ai.core.StreamWatchdog;
import ai.lambda.ai.core.*;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A client for the OpenAI <b>Responses</b> protocol, which Perplexity's Agent API uses:
 *
 * <pre>
 * ModelClient sonar = ResponsesModelClient.perplexity(key, "perplexity/sonar");
 * ModelClient gpt = ResponsesModelClient.perplexity(key, "openai/gpt-5.6-luna");
 * </pre>
 *
 * The system prompt is sent as {@code instructions}, tool calls and results as
 * {@code function_call} / {@code function_call_output} items, and images as {@code input_image}.
 * Perplexity's {@code thought_signature} on tool calls is kept and sent back, as it requires.
 */
public final class ResponsesModelClient implements ModelClient {

    private static final String PERPLEXITY_BASE_URL = "https://api.perplexity.ai/v1";

    private final String providerName;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final HttpOptions options;
    private final HttpClient httpClient;
    private final ModelCapabilities capabilities;
    private final int maxOutputTokens;

    /**
     * @param maxOutputTokens the longest answer, in tokens (Perplexity requires it for some models)
     */
    public ResponsesModelClient(String providerName, String baseUrl, String apiKey, String model,
                                ModelCapabilities capabilities, int maxOutputTokens, HttpOptions options) {
        this.providerName = Objects.requireNonNull(providerName, "providerName must not be null");
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException(providerName + " needs an API key");
        this.apiKey = apiKey;
        this.model = Objects.requireNonNull(model, "model must not be null");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities must not be null");
        if (maxOutputTokens < 1) throw new IllegalArgumentException("maxOutputTokens must be at least 1");
        this.maxOutputTokens = maxOutputTokens;
        this.options = Objects.requireNonNull(options, "options must not be null");
        this.httpClient = HttpRetry.newClient(options);
    }

    /**
     * Perplexity's Agent API. Models are named {@code provider/model} (for example
     * {@code perplexity/sonar}, {@code anthropic/claude-sonnet-4-6}); images (files or URLs) are
     * accepted, and tools are supported.
     */
    public static ResponsesModelClient perplexity(String apiKey, String model) {
        return new ResponsesModelClient("Perplexity", PERPLEXITY_BASE_URL, apiKey, model,
                ModelCapabilities.of(Modality.IMAGE).withMediaUrls(Modality.IMAGE), 16_000, HttpOptions.defaults());
    }

    /** A copy with different capabilities. */
    public ResponsesModelClient withCapabilities(ModelCapabilities capabilities) {
        return new ResponsesModelClient(providerName, baseUrl, apiKey, model, capabilities, maxOutputTokens, options);
    }

    /** A copy pointing at another address, for example a proxy or a test server. */
    public ResponsesModelClient withBaseUrl(String baseUrl) {
        return new ResponsesModelClient(providerName, baseUrl, apiKey, model, capabilities, maxOutputTokens, options);
    }

    @Override
    public ModelCapabilities capabilities() {
        return capabilities;
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<ToolSchema> tools) {
        HttpResponse<String> response = HttpRetry.send(httpClient, request(body(messages, tools, false)),
                HttpResponse.BodyHandlers.ofString(), options, providerName);
        if (response.statusCode() >= 400) {
            throw new RuntimeException(providerName + " error: " + response.statusCode() + " " + response.body());
        }
        return toChatResponse(new JSONObject(response.body()), null);
    }

    @Override
    public ChatResponse streamChat(List<Message> messages, List<ToolSchema> tools, Consumer<String> onDelta) {
        HttpResponse<Stream<String>> response = HttpRetry.send(httpClient, request(body(messages, tools, true)),
                info -> info.statusCode() < 400
                        ? HttpResponse.BodySubscribers.ofLines(StandardCharsets.UTF_8)
                        : HttpResponse.BodySubscribers.mapping(HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8), Stream::of),
                options, providerName);

        StringBuilder streamed = new StringBuilder();
        JSONObject completed = null;
        try (Stream<String> lines = response.body();
             StreamWatchdog watchdog = new StreamWatchdog(providerName, options.requestTimeout(), lines::close)) {
            if (response.statusCode() >= 400) {
                throw new RuntimeException(providerName + " streaming error: " + response.statusCode() + " "
                        + JsonHttp.shorten(lines.collect(Collectors.joining("\n"))));
            }
            try {
                for (String line : (Iterable<String>) lines::iterator) {
                    watchdog.activity(); // keep-alive comments count too: the provider is still there
                    if (!line.startsWith("data:")) continue; // "event:" lines repeat the type found in the data
                    String data = line.substring(5).trim();
                    if (data.isEmpty() || data.equals("[DONE]")) continue;
                    JSONObject event = new JSONObject(data);
                    switch (event.optString("type")) {
                        case "response.output_text.delta" -> {
                            String delta = event.optString("delta", "");
                            streamed.append(delta);
                            if (onDelta != null && !delta.isEmpty()) onDelta.accept(delta);
                        }
                        case "response.completed", "response.incomplete" -> completed = event.getJSONObject("response");
                        case "response.failed", "error" -> throw new RuntimeException(providerName + " error: " + errorMessage(event));
                        default -> {
                        }
                    }
                }
            } catch (RuntimeException failure) {
                throw watchdog.explain(failure);
            }
            watchdog.check();
        }
        if (completed == null) throw new RuntimeException(providerName + " stream ended without a final response");
        return toChatResponse(completed, streamed.toString());
    }

    private static String errorMessage(JSONObject event) {
        JSONObject error = event.optJSONObject("error");
        if (error == null && event.optJSONObject("response") != null) error = event.getJSONObject("response").optJSONObject("error");
        return error != null ? error.optString("message", error.toString()) : event.toString();
    }

    private HttpRequest request(JSONObject body) {
        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/responses"))
                .timeout(options.requestTimeout())
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
    }

    JSONObject body(List<Message> messages, List<ToolSchema> tools, boolean stream) {
        capabilities.check(messages, tools, providerName, model);
        JSONObject body = new JSONObject().put("model", model).put("stream", stream).put("max_output_tokens", maxOutputTokens);
        JSONArray input = new JSONArray();
        boolean instructionsSet = false;
        for (Message message : messages) {
            switch (message.getRole()) {
                case SYSTEM -> {
                    if (!instructionsSet) {
                        body.put("instructions", message.getContent());
                        instructionsSet = true;
                    } else {
                        input.put(new JSONObject().put("role", "system").put("content", message.getContent()));
                    }
                }
                case USER -> input.put(userItem(message));
                case ASSISTANT -> {
                    if (!message.getContent().isEmpty()) {
                        input.put(new JSONObject().put("role", "assistant").put("content", message.getContent()));
                    }
                    for (ToolCall call : message.getToolCalls()) {
                        JSONObject item = new JSONObject().put("type", "function_call").put("call_id", call.getId())
                                .put("name", call.getName()).put("arguments", call.getArgumentsJson());
                        if (call.getSignature() != null) item.put("thought_signature", call.getSignature());
                        input.put(item);
                    }
                }
                case TOOL -> input.put(new JSONObject().put("type", "function_call_output")
                        .put("call_id", message.getToolCallId()).put("output", message.getContent()));
            }
        }
        body.put("input", input);
        if (tools != null && !tools.isEmpty()) {
            JSONArray declarations = new JSONArray();
            for (ToolSchema tool : tools) {
                declarations.put(new JSONObject().put("type", "function").put("name", tool.getName())
                        .put("description", tool.getDescription()).put("parameters", new JSONObject(tool.getJsonSchema())));
            }
            body.put("tools", declarations);
        }
        return body;
    }

    private JSONObject userItem(Message message) {
        if (message.getMedia().isEmpty()) return new JSONObject().put("role", "user").put("content", message.getContent());
        JSONArray content = new JSONArray();
        if (!message.getContent().isEmpty()) content.put(new JSONObject().put("type", "input_text").put("text", message.getContent()));
        for (Media media : message.getMedia()) {
            if (media.modality() != Modality.IMAGE) {
                throw new UnsupportedMediaException(providerName + " accepts only images, not " + media.mimeType());
            }
            content.put(new JSONObject().put("type", "input_image").put("image_url", media.dataUrl()));
        }
        return new JSONObject().put("role", "user").put("content", content);
    }

    private ChatResponse toChatResponse(JSONObject response, String streamedText) {
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        JSONArray output = response.optJSONArray("output");
        if (output != null) {
            for (int i = 0; i < output.length(); i++) {
                JSONObject item = output.getJSONObject(i);
                if (item.optString("type").equals("message")) {
                    JSONArray content = item.optJSONArray("content");
                    for (int j = 0; content != null && j < content.length(); j++) {
                        JSONObject part = content.getJSONObject(j);
                        if (part.optString("type").equals("output_text")) text.append(part.optString("text", ""));
                    }
                } else if (item.optString("type").equals("function_call")) {
                    calls.add(new ToolCall(item.getString("call_id"), item.getString("name"),
                            item.optString("arguments", "{}"), item.optString("thought_signature", null)));
                }
            }
        }
        String content = text.length() > 0 ? text.toString() : (streamedText == null ? "" : streamedText);
        FinishReason reason = !calls.isEmpty() ? FinishReason.TOOL_CALLS
                : switch (response.optString("status")) {
                    case "completed" -> FinishReason.STOP;
                    case "incomplete" -> FinishReason.LENGTH;
                    default -> FinishReason.UNKNOWN;
                };
        JSONObject usage = response.optJSONObject("usage");
        ModelUsage modelUsage = usage == null ? ModelUsage.empty()
                : new ModelUsage(usage.optLong("input_tokens", 0), usage.optLong("output_tokens", 0), usage.optLong("total_tokens", 0));
        return new ChatResponse(new Message(Role.ASSISTANT, content, null, null, calls), calls, reason, modelUsage);
    }
}
