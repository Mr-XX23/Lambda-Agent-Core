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
import java.util.List;
import java.util.Objects;

public class OpenAIModelClient implements ModelClient {

    private final HttpClient httpClient;
    private final HttpOptions options;
    private final String apiKey;
    private final String model;

    public OpenAIModelClient(String apiKey, String model) {
        this(apiKey, model, HttpOptions.defaults());
    }

    public OpenAIModelClient(String apiKey, String model, HttpOptions options) {
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey must not be null");
        this.model = Objects.requireNonNull(model, "model must not be null");
        this.options = Objects.requireNonNull(options, "options must not be null");
        this.httpClient = HttpRetry.newClient(options);
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<ToolSchema> tools) {
        JSONObject requestBody = new JSONObject();
        requestBody.put("model", model);

        JSONArray jsonMessages = new JSONArray();
        for (Message m : messages) {
            JSONObject jm = new JSONObject();
            jm.put("role", toOpenAiRole(m.getRole()));
            jm.put("content", m.getContent());
            jsonMessages.put(jm);
        }
        requestBody.put("messages", jsonMessages);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/chat/completions"))
                .timeout(options.requestTimeout())
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = HttpRetry.send(
                httpClient, request, HttpResponse.BodyHandlers.ofString(), options, "OpenAI");

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

        Message assistantMessage = new Message(Role.ASSISTANT, content, null);
        return new ChatResponse(assistantMessage, List.of());
    }

    @Override
    public ChatResponse streamChat(List<Message> messages, List<ToolSchema> tools, Consumer<String> onDelta) {
        // For now, streaming is not supported for OpenAI in this implementation.
        // We'll just fall back to the synchronous chat and fire one big delta at the end,
        // or just throw for now to be honest about support.
        throw new UnsupportedOperationException("Streaming not yet implemented for OpenAI.");
    }

    @Override
    public int countTokens(List<Message> messages) {
        // Simple approximation for OpenAI if no tokenizer is available.
        // Usually ~4 chars per token for English.
        int totalChars = 0;
        for (Message m : messages) {
            totalChars += m.getContent().length();
        }
        return totalChars / 4;
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