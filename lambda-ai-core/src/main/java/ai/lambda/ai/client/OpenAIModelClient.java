package ai.lambda.ai.client;

import ai.lambda.ai.core.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.Map;

public class OpenAIModelClient implements ModelClient {

    private final HttpClient httpClient;
    private final String apiKey;
    private final String model;

    public OpenAIModelClient(String apiKey, String model) {
        this.httpClient = HttpClient.newHttpClient();
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey must not be null");
        this.model = Objects.requireNonNull(model, "model must not be null");
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<ToolSchema> tools) {
        try {

            JSONObject requestBody = buildRequestBody(messages, tools, false);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.openai.com/v1/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString(), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient
                    .send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                throw new RuntimeException("OpenAI error: " + response.statusCode() + " " + response.body());
            }

            JSONObject body = new JSONObject(response.body());
            JSONArray choices = body.getJSONArray("choices");
            if (choices.isEmpty()) {
                throw new RuntimeException("OpenAI returned no choices");
            }
            JSONObject first = choices.getJSONObject(0);
            JSONObject message = first.getJSONObject("message");
            String content = message.optString("content", "");
            List<ToolCall> toolCalls = new ArrayList<>();
            JSONArray responseToolCalls = message.optJSONArray("tool_calls");
            if (responseToolCalls != null) {
                for (int i = 0; i < responseToolCalls.length(); i++) {
                    JSONObject toolCall = responseToolCalls.getJSONObject(i);
                    JSONObject function = toolCall.getJSONObject("function");
                    toolCalls.add(new ToolCall(
                            toolCall.getString("id"),
                            function.getString("name"),
                            function.optString("arguments", "{}")));
                }
            }

            Message assistantMessage = new Message(Role.ASSISTANT, content, null, null, toolCalls);
            return new ChatResponse(assistantMessage, toolCalls,
                    toFinishReason(first.optString("finish_reason", "")), parseUsage(body));

        } catch (IOException e) {
            throw new RuntimeException("Failed to call OpenAI", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to call OpenAI", e);
        }
    }

    @Override
    public ChatResponse streamChat(List<Message> messages, List<ToolSchema> tools, Consumer<String> onDelta) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.openai.com/v1/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(
                            buildRequestBody(messages, tools, true).toString(), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<java.util.stream.Stream<String>> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() >= 400) {
                throw new RuntimeException("OpenAI streaming error: " + response.statusCode());
            }
            StringBuilder text = new StringBuilder();
            Map<Integer, StreamToolCall> calls = new LinkedHashMap<>();
            FinishReason finishReason = FinishReason.UNKNOWN;
            ModelUsage usage = ModelUsage.empty();
            try (var lines = response.body()) {
                for (String line : (Iterable<String>) lines::iterator) {
                    if (!line.startsWith("data: ")) continue;
                    String data = line.substring(6).trim();
                    if ("[DONE]".equals(data)) break;
                    JSONObject chunk = new JSONObject(data);
                    usage = parseUsage(chunk);
                    JSONArray choices = chunk.optJSONArray("choices");
                    if (choices == null || choices.isEmpty()) continue;
                    JSONObject choice = choices.getJSONObject(0);
                    finishReason = toFinishReason(choice.optString("finish_reason", ""));
                    JSONObject delta = choice.optJSONObject("delta");
                    if (delta == null) continue;
                    String piece = delta.optString("content", "");
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
                            if (item.has("id")) call.id = item.getString("id");
                            JSONObject function = item.optJSONObject("function");
                            if (function != null) {
                                if (function.has("name")) call.name = function.getString("name");
                                call.arguments.append(function.optString("arguments", ""));
                            }
                        }
                    }
                }
            }
            List<ToolCall> toolCalls = calls.values().stream()
                    .map(call -> new ToolCall(call.id, call.name, call.arguments.toString()))
                    .toList();
            return new ChatResponse(new Message(Role.ASSISTANT, text.toString(), null, null, toolCalls),
                    toolCalls, finishReason, usage);
        } catch (IOException error) {
            throw new RuntimeException("Failed to call OpenAI streaming", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to call OpenAI streaming", error);
        }
    }

    private JSONObject buildRequestBody(List<Message> messages, List<ToolSchema> tools, boolean stream) {
        JSONObject requestBody = new JSONObject().put("model", model).put("stream", stream);
        if (stream) {
            requestBody.put("stream_options", new JSONObject().put("include_usage", true));
        }
        JSONArray jsonMessages = new JSONArray();
        for (Message message : messages) {
            JSONObject json = new JSONObject().put("role", toOpenAiRole(message.getRole()))
                    .put("content", message.getContent());
            if (message.getRole() == Role.TOOL && message.getToolCallId() != null) {
                json.put("tool_call_id", message.getToolCallId());
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
            jsonMessages.put(json);
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

    static ModelUsage parseUsage(JSONObject body) {
        JSONObject usage = body.optJSONObject("usage");
        if (usage == null) return ModelUsage.empty();
        return new ModelUsage(usage.optLong("prompt_tokens", 0),
                usage.optLong("completion_tokens", 0), usage.optLong("total_tokens", 0));
    }

    static FinishReason toFinishReason(String reason) {
        return switch (reason) {
            case "stop" -> FinishReason.STOP;
            case "tool_calls", "function_call" -> FinishReason.TOOL_CALLS;
            case "length" -> FinishReason.LENGTH;
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