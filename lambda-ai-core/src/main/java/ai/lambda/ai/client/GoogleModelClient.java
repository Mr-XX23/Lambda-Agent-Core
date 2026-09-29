package ai.lambda.ai.client;

import ai.lambda.ai.core.*;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class GoogleModelClient implements ModelClient {

    private static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com";

    private final HttpClient httpClient;
    private final HttpOptions options;
    private final String baseUrl;
    private final String apiKey;
    private final String modelName;

    public GoogleModelClient(String apiKey, String modelName) {
        this(apiKey, modelName, HttpOptions.defaults());
    }

    public GoogleModelClient(String apiKey, String modelName, HttpOptions options) {
        this(apiKey, modelName, options, DEFAULT_BASE_URL);
    }

    // Visible for tests: lets a local server stand in for the Gemini API.
    GoogleModelClient(String apiKey, String modelName, HttpOptions options, String baseUrl) {
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey must not be null");
        this.modelName = Objects.requireNonNull(modelName, "modelName must not be null");
        this.options = Objects.requireNonNull(options, "options must not be null");
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        this.httpClient = HttpRetry.newClient(options);
    }

    /** What has arrived so far while reading a streamed response. */
    private static final class StreamState {
        final StringBuilder text = new StringBuilder();
        final List<ToolCall> toolCalls = new ArrayList<>();
        ModelUsage usage = ModelUsage.empty();
        FinishReason finishReason = FinishReason.UNKNOWN;
    }

    @Override
    public ChatResponse streamChat(List<Message> messages, List<ToolSchema> tools, Consumer<String> onDelta) {
        JSONObject requestBody = buildRequestBody(messages, tools);
        HttpRequest request = buildRequest("streamGenerateContent", "alt=sse&", requestBody);

        HttpResponse<Stream<String>> response = HttpRetry.send(httpClient, request, linesOrErrorBody(), options, "Gemini");

        StreamState state = new StreamState();
        StringBuilder sseBuffer = new StringBuilder();

        try (Stream<String> lines = response.body()) {
            if (response.statusCode() >= 400) {
                throw new RuntimeException("Gemini streaming error: " + response.statusCode() + " "
                        + lines.collect(Collectors.joining("\n")));
            }

            lines.forEach(line -> {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    // SSE event separator: process what we have in the buffer
                    if (sseBuffer.length() > 0) {
                        processSseChunk(sseBuffer.toString(), state, onDelta);
                        sseBuffer.setLength(0);
                    }
                } else if (trimmed.startsWith("data: ")) {
                    sseBuffer.append(trimmed.substring(6));
                }
            });
        }

        // Final chunk if no trailing newline
        if (sseBuffer.length() > 0) {
            processSseChunk(sseBuffer.toString(), state, onDelta);
        }

        if (state.text.length() == 0 && state.toolCalls.isEmpty()) {
            System.out.println("[DEBUG] Warning: Gemini returned an empty response. Status: " + response.statusCode());
        }

        // The tool calls must stay on the message: Gemini needs to see its own functionCall
        // (with its thoughtSignature) before the matching functionResponse on the next turn.
        Message assistantMessage = new Message(Role.ASSISTANT, state.text.toString(), null, null, state.toolCalls);
        return new ChatResponse(assistantMessage, state.toolCalls,
                state.toolCalls.isEmpty() ? state.finishReason : FinishReason.TOOL_CALLS, state.usage);
    }

    // Streams lines on success; on an error status, reads the whole body so it can go in the exception.
    private static HttpResponse.BodyHandler<Stream<String>> linesOrErrorBody() {
        return info -> info.statusCode() < 400
                ? HttpResponse.BodySubscribers.ofLines(StandardCharsets.UTF_8)
                : HttpResponse.BodySubscribers.mapping(
                        HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8), Stream::of);
    }

    private void processSseChunk(String jsonData, StreamState state, Consumer<String> onDelta) {
        if (jsonData.equals("[DONE]")) return;
        try {
            JSONObject chunk = new JSONObject(jsonData);
            if (chunk.has("usageMetadata")) {
                state.usage = parseUsage(chunk);
            }
            JSONArray candidates = chunk.optJSONArray("candidates");
            if (candidates != null && !candidates.isEmpty()) {
                JSONObject first = candidates.getJSONObject(0);

                String reason = first.optString("finishReason", "");
                if (!reason.isEmpty()) {
                    state.finishReason = toFinishReason(reason, false);
                }

                // Debug finish reason if no content
                if (!first.has("content") && !"STOP".equals(reason)) {
                    System.out.println("[DEBUG] Candidate finish reason: " + (reason.isEmpty() ? "UNKNOWN" : reason));
                }

                JSONObject content = first.optJSONObject("content");
                if (content != null) {
                    JSONArray parts = content.optJSONArray("parts");
                    if (parts != null) {
                        for (int i = 0; i < parts.length(); i++) {
                            JSONObject p = parts.getJSONObject(i);
                            if (p.has("text")) {
                                String delta = p.getString("text");
                                state.text.append(delta);
                                if (onDelta != null) {
                                    onDelta.accept(delta);
                                }
                            } else if (p.has("functionCall")) {
                                state.toolCalls.add(parseFunctionCall(p));
                            }
                        }
                    }
                }
            } else if (chunk.has("error")) {
                System.out.println("[DEBUG] Gemini Stream Error: " + chunk.get("error"));
            }
        } catch (Exception e) {
            // Log parsing errors for diagnosis
            System.err.println("[DEBUG] Failed to parse SSE JSON: " + jsonData + " Error: " + e.getMessage());
        }
    }

    private static ToolCall parseFunctionCall(JSONObject part) {
        JSONObject fc = part.getJSONObject("functionCall");
        String name = fc.optString("name", "");
        JSONObject args = fc.optJSONObject("args");
        String argsJson = args != null ? args.toString() : "{}";
        return new ToolCall(UUID.randomUUID().toString(), name, argsJson, part.optString("thoughtSignature", null));
    }

    private HttpRequest buildRequest(String method, String query, JSONObject body) {
        String url = baseUrl + "/v1beta/models/"
                + URLEncoder.encode(modelName, StandardCharsets.UTF_8)
                + ":" + method + "?" + query + "key="
                + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);

        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(options.requestTimeout())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
    }

    private JSONObject buildRequestBody(List<Message> messages, List<ToolSchema> tools) {
        JSONObject requestBody = new JSONObject();

        // 1. Handle SYSTEM instruction separately
        for (Message m : messages) {
            if (m.getRole() == Role.SYSTEM) {
                JSONObject systemInstruction = new JSONObject();
                JSONArray parts = new JSONArray();
                JSONObject textPart = new JSONObject();
                textPart.put("text", m.getContent());
                parts.put(textPart);
                systemInstruction.put("parts", parts);
                requestBody.put("system_instruction", systemInstruction);
                break; // Only one system instruction
            }
        }

        // 2. Build contents, filtering out SYSTEM and ensuring alternating roles.
        // Gemini only knows "user" and "model", so TOOL results are sent as "user".
        JSONArray contents = new JSONArray();
        String currentRole = null;
        JSONObject currentContent = null;

        for (Message m : messages) {
            if (m.getRole() == Role.SYSTEM) continue;

            String geminiRole = (m.getRole() == Role.ASSISTANT) ? "model" : "user";

            // If the role is the same as the previous one, we must merge them
            if (geminiRole.equals(currentRole)) {
                addPartsToContent(currentContent, m);
            } else {
                currentContent = new JSONObject();
                currentContent.put("role", geminiRole);
                JSONArray parts = new JSONArray();
                currentContent.put("parts", parts);
                addPartsToContent(currentContent, m);
                contents.put(currentContent);
                currentRole = geminiRole;
            }
        }

        requestBody.put("contents", contents);

        if (tools != null && !tools.isEmpty()) {
            JSONArray toolArray = new JSONArray();
            JSONObject toolObj = new JSONObject();
            JSONArray functionDecls = new JSONArray();

            for (ToolSchema ts : tools) {
                JSONObject fn = new JSONObject();
                fn.put("name", ts.getName());
                fn.put("description", ts.getDescription());
                if (ts.getJsonSchema() != null && !ts.getJsonSchema().isBlank()) {
                    // parametersJsonSchema takes standard JSON Schema. The older "parameters" field only
                    // takes an OpenAPI subset and rejects keys such as additionalProperties, which
                    // tools from MCP servers often use.
                    fn.put("parametersJsonSchema", new JSONObject(ts.getJsonSchema()));
                }
                functionDecls.put(fn);
            }

            toolObj.put("functionDeclarations", functionDecls);
            toolArray.put(toolObj);
            requestBody.put("tools", toolArray);

            JSONObject toolConfig = new JSONObject();
            JSONObject functionCallingConfig = new JSONObject();
            functionCallingConfig.put("mode", "AUTO");
            toolConfig.put("functionCallingConfig", functionCallingConfig);
            requestBody.put("toolConfig", toolConfig);
        }

        return requestBody;
    }

    private void addPartsToContent(JSONObject content, Message m) {
        JSONArray parts = content.getJSONArray("parts");
        if (m.getRole() == Role.TOOL) {
            JSONObject functionResponse = new JSONObject();
            functionResponse.put("name", m.getToolCallName() != null ? m.getToolCallName() : "unknown");
            JSONObject responseObj = new JSONObject();
            responseObj.put("result", m.getContent());
            functionResponse.put("response", responseObj);

            JSONObject part = new JSONObject();
            part.put("functionResponse", functionResponse);
            parts.put(part);
        } else {
            if (m.getContent() != null && !m.getContent().isEmpty()) {
                JSONObject textPart = new JSONObject();
                textPart.put("text", m.getContent());
                parts.put(textPart);
            }

            if (m.getRole() == Role.ASSISTANT && m.getToolCalls() != null && !m.getToolCalls().isEmpty()) {
                for (ToolCall tc : m.getToolCalls()) {
                    JSONObject functionCall = new JSONObject();
                    functionCall.put("name", tc.getName());
                    functionCall.put("args", new JSONObject(tc.getArgumentsJson()));

                    JSONObject part = new JSONObject();
                    part.put("functionCall", functionCall);
                    if (tc.getSignature() != null) {
                        part.put("thoughtSignature", tc.getSignature());
                    }
                    parts.put(part);
                }
            }
        }

        // Ensure at least one part exists
        if (parts.isEmpty()) {
            JSONObject emptyText = new JSONObject();
            emptyText.put("text", " ");
            parts.put(emptyText);
        }
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<ToolSchema> tools) {
        JSONObject requestBody = buildRequestBody(messages, tools);
        HttpRequest request = buildRequest("generateContent", "", requestBody);

        HttpResponse<String> response = HttpRetry.send(
                httpClient, request, HttpResponse.BodyHandlers.ofString(), options, "Gemini");
        if (response.statusCode() >= 400) {
            throw new RuntimeException("Gemini error: " + response.statusCode() + " " + response.body());
        }

        JSONObject body = new JSONObject(response.body());
        JSONArray candidates = body.optJSONArray("candidates");
        if (candidates == null || candidates.isEmpty()) {
            throw new RuntimeException("Gemini returned no candidates. Body: " + response.body());
        }

        JSONObject first = candidates.getJSONObject(0);
        JSONObject content = first.optJSONObject("content");

        List<ToolCall> toolCalls = new ArrayList<>();
        StringBuilder assistantText = new StringBuilder();

        if (content != null) {
            JSONArray parts = content.optJSONArray("parts");
            if (parts != null) {
                for (int i = 0; i < parts.length(); i++) {
                    JSONObject p = parts.getJSONObject(i);
                    if (p.has("functionCall")) {
                        toolCalls.add(parseFunctionCall(p));
                    } else if (p.has("text")) {
                        assistantText.append(p.optString("text", ""));
                    }
                }
            }
        }

        Message assistantMessage = new Message(Role.ASSISTANT, assistantText.toString(), null, null, toolCalls);
        return new ChatResponse(assistantMessage, toolCalls,
                toFinishReason(first.optString("finishReason", ""), !toolCalls.isEmpty()), parseUsage(body));
    }

    @Override
    public int countTokens(List<Message> messages) {
        JSONObject requestBody = buildRequestBody(messages, List.of());
        HttpRequest request = buildRequest("countTokens", "", requestBody);

        HttpResponse<String> response = HttpRetry.send(
                httpClient, request, HttpResponse.BodyHandlers.ofString(), options, "Gemini");
        if (response.statusCode() >= 400) {
            throw new RuntimeException("Gemini countTokens error: " + response.statusCode() + " " + response.body());
        }

        JSONObject body = new JSONObject(response.body());
        return body.optInt("totalTokens", 0);
    }

    static ModelUsage parseUsage(JSONObject body) {
        JSONObject usage = body.optJSONObject("usageMetadata");
        if (usage == null) {
            return ModelUsage.empty();
        }
        return new ModelUsage(usage.optLong("promptTokenCount", 0),
                usage.optLong("candidatesTokenCount", 0),
                usage.optLong("totalTokenCount", 0));
    }

    static FinishReason toFinishReason(String reason, boolean hasToolCalls) {
        if (hasToolCalls) {
            return FinishReason.TOOL_CALLS;
        }
        return switch (reason == null ? "" : reason.toUpperCase(Locale.ROOT)) {
            case "STOP" -> FinishReason.STOP;
            case "MAX_TOKENS", "RECITATION" -> FinishReason.LENGTH;
            case "SAFETY", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII" ->
                    FinishReason.CONTENT_FILTER;
            case "MALFORMED_FUNCTION_CALL" -> FinishReason.TOOL_CALLS;
            default -> FinishReason.UNKNOWN;
        };
    }
}
