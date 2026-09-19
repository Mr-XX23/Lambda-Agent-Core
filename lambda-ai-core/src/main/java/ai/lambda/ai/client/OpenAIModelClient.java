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

            JSONObject requestBody = new JSONObject();
            requestBody.put("model", model);

            JSONArray jsonMessages = new JSONArray();
            for (Message m : messages) {
                JSONObject jm = new JSONObject();
                jm.put("role", toOpenAiRole(m.getRole()));
                if (m.getRole() == Role.TOOL) {
                    jm.put("content", m.getContent());
                    if (m.getToolCallId() != null) {
                        jm.put("tool_call_id", m.getToolCallId());
                    }
                } else {
                    jm.put("content", m.getContent());
                    if (m.getRole() == Role.ASSISTANT && !m.getToolCalls().isEmpty()) {
                        JSONArray calls = new JSONArray();
                        for (ToolCall call : m.getToolCalls()) {
                            JSONObject function = new JSONObject();
                            function.put("name", call.getName());
                            function.put("arguments", call.getArgumentsJson());
                            JSONObject toolCall = new JSONObject();
                            toolCall.put("id", call.getId());
                            toolCall.put("type", "function");
                            toolCall.put("function", function);
                            calls.put(toolCall);
                        }
                        jm.put("tool_calls", calls);
                    }
                }
                jsonMessages.put(jm);
            }
            requestBody.put("messages", jsonMessages);
            if (tools != null && !tools.isEmpty()) {
                JSONArray jsonTools = new JSONArray();
                for (ToolSchema tool : tools) {
                    JSONObject function = new JSONObject();
                    function.put("name", tool.getName());
                    function.put("description", tool.getDescription());
                    function.put("parameters", tool.getJsonSchema() == null || tool.getJsonSchema().isBlank()
                            ? new JSONObject()
                            : new JSONObject(tool.getJsonSchema()));
                    JSONObject jsonTool = new JSONObject();
                    jsonTool.put("type", "function");
                    jsonTool.put("function", function);
                    jsonTools.put(jsonTool);
                }
                requestBody.put("tools", jsonTools);
            }

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
            return new ChatResponse(assistantMessage, toolCalls);

        } catch (IOException e) {
            throw new RuntimeException("Failed to call OpenAI", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to call OpenAI", e);
        }
    }

    @Override
    public ChatResponse streamChat(List<Message> messages, List<ToolSchema> tools, Consumer<String> onDelta) {
        ChatResponse response = chat(messages, tools);
        if (onDelta != null && !response.getAssistantMessage().getContent().isEmpty()) {
            onDelta.accept(response.getAssistantMessage().getContent());
        }
        return response;
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