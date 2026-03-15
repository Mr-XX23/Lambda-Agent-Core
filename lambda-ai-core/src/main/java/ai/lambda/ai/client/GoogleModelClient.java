package ai.lambda.ai.client;

import ai.lambda.ai.core.*;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class GoogleModelClient implements ModelClient {

    private final HttpClient httpClient;
    private final String apiKey;
    private final String modelName;

    public GoogleModelClient(String apiKey, String modelName) {
        this.httpClient = HttpClient.newHttpClient();
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey must not be null");
        this.modelName = Objects.requireNonNull(modelName, "modelName must not be null");
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<ToolSchema> tools) {

        try {
            JSONObject requestBody = new JSONObject();
            JSONArray contents = new JSONArray();

            for (Message m : messages) {

                JSONObject c = new JSONObject();

                String geminiRole = (m.getRole() == Role.ASSISTANT) ? "model" : "user";
                c.put("role", geminiRole);

                JSONArray parts = new JSONArray();

                if (m.getRole() == Role.TOOL) {
                    // 1) Format Tool Results strictly as Gemini functionResponse
                    JSONObject functionResponse = new JSONObject();
                    functionResponse.put("name", m.getToolCallName() != null ? m.getToolCallName() : "unknown");

                    JSONObject responseObj = new JSONObject();
                    responseObj.put("result", m.getContent());
                    functionResponse.put("response", responseObj);

                    JSONObject part = new JSONObject();
                    part.put("functionResponse", functionResponse);
                    parts.put(part);
                } else {
                    // 2) Format normal text
                    if (!m.getContent().isEmpty()) {
                        JSONObject textPart = new JSONObject();
                        textPart.put("text", m.getContent());
                        parts.put(textPart);
                    }

                    // 3) Reconstruct Model's past function calls into the history
                    if (m.getRole() == Role.ASSISTANT && !m.getToolCalls().isEmpty()) {
                        for (ToolCall tc : m.getToolCalls()) {
                            JSONObject functionCall = new JSONObject();
                            functionCall.put("name", tc.getName());
                            functionCall.put("args", new JSONObject(tc.getArgumentsJson()));

                            JSONObject part = new JSONObject();
                            part.put("functionCall", functionCall);
                            parts.put(part);
                        }
                    }
                }
                // Gemini throws 400 Bad Request if parts array is completely empty
                if (parts.isEmpty()) {
                    JSONObject emptyText = new JSONObject();
                    emptyText.put("text", " ");
                    parts.put(emptyText);
                }

                c.put("parts", parts);
                contents.put(c);
            }

            requestBody.put("contents", contents);

            // 2) tools -> Gemini functionDeclarations
            if (tools != null && !tools.isEmpty()) {

                JSONArray toolArray = new JSONArray();
                JSONObject toolObj = new JSONObject();
                JSONArray functionDecls = new JSONArray();

                for (ToolSchema ts : tools) {
                    JSONObject fn = new JSONObject();
                    fn.put("name", ts.getName());
                    fn.put("description", ts.getDescription());
                    // used as "parameters".
                    if (ts.getJsonSchema() != null && !ts.getJsonSchema().isBlank()) {
                        fn.put("parameters", new JSONObject(ts.getJsonSchema()));
                    }

                    functionDecls.put(fn);
                }

                toolObj.put("functionDeclarations", functionDecls);
                toolArray.put(toolObj);
                requestBody.put("tools", toolArray);

                // Enable function calling in "AUTO" mode so the model can decide.
                JSONObject toolConfig = new JSONObject();
                JSONObject functionCallingConfig = new JSONObject();
                functionCallingConfig.put("mode", "AUTO");
                toolConfig.put("functionCallingConfig", functionCallingConfig);
                requestBody.put("toolConfig", toolConfig);
            }

            String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                    + URLEncoder.encode(modelName, StandardCharsets.UTF_8)
                    + ":generateContent?key="
                    + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString(), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new RuntimeException("Gemini error: " + response.statusCode() + " " + response.body());
            }

            // DEBUG : print the raw response from Gemini for visibility
            System.out.println("[GoogleModelClient] Gemini raw response: " + response.body());

            JSONObject body = new JSONObject(response.body());
            JSONArray candidates = body.optJSONArray("candidates");
            if (candidates == null || candidates.isEmpty()) {
                throw new RuntimeException("Gemini returned no candidates. Body: " + response.body());
            }

            JSONObject first = candidates.getJSONObject(0);
            JSONObject content = first.optJSONObject("content");

            // 3) Extract functionCall(s) and/or text from parts
            List<ToolCall> toolCalls = new ArrayList<>();
            StringBuilder assistantText = new StringBuilder();

            // Only process parts if content actually exists and has parts
            if( content != null ) {
                JSONArray parts = content.optJSONArray("parts");
                if ( parts != null ) {
                    for (int i = 0; i < parts.length(); i++) {
                        JSONObject p = parts.getJSONObject(i);
                        if (p.has("functionCall")) {
                            JSONObject fc = p.getJSONObject("functionCall");
                            String name = fc.optString("name", "");
                            JSONObject args = fc.optJSONObject("args");
                            String argsJson = args != null ? args.toString() : "{}";

                            // Gemini doesn't return an id; we generate one.
                            String id = UUID.randomUUID().toString();
                            toolCalls.add(new ToolCall(id, name, argsJson));
                        } else if (p.has("text")) {
                            assistantText.append(p.optString("text", ""));
                        }
                    }
                }
            }

            Message assistantMessage = new Message(Role.ASSISTANT, assistantText.toString(), null);
            return new ChatResponse(assistantMessage, toolCalls);

        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to call Gemini", e);
        }
    }

    private static String toGeminiRole(Role role) {

        // Gemini uses "user" and "model". We'll map both SYSTEM and USER to "user".
        return switch (role) {
            case USER, SYSTEM -> "user";
            case ASSISTANT, TOOL -> "model";
        };
    }
}
