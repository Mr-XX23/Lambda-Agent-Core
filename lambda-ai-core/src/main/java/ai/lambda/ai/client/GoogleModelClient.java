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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

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
    public ChatResponse streamChat(List<Message> messages, List<ToolSchema> tools, Consumer<String> onDelta) {

        try {
            JSONObject requestBody = buildRequestBody(messages, tools);

            String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                    + URLEncoder.encode(modelName, StandardCharsets.UTF_8)
                    + ":streamGenerateContent?alt=sse&key="
                    + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString(), StandardCharsets.UTF_8))
                    .build();

            // We use ofLines() to process the SSE stream line by line.
            HttpResponse<java.util.stream.Stream<String>> response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());

            if (response.statusCode() >= 400) {
                // Read the body if possible for error message, but ofLines makes it tricky if it's already a stream.
                throw new RuntimeException("Gemini streaming error: " + response.statusCode());
            }

            StringBuilder fullText = new StringBuilder();
            List<ToolCall> allToolCalls = new ArrayList<>();
            AtomicReference<ModelUsage> usage = new AtomicReference<>(ModelUsage.empty());
            AtomicReference<FinishReason> finishReason = new AtomicReference<>(FinishReason.UNKNOWN);

            response.body().forEach(line -> {
                if (line.startsWith("data: ")) {
                    String jsonData = line.substring(6);
                    JSONObject chunk = new JSONObject(jsonData);
                    usage.set(parseUsage(chunk));
                    
                    // Gemini stream produces many candidates, usually just one per chunk.
                    JSONArray candidates = chunk.optJSONArray("candidates");
                    if (candidates != null && !candidates.isEmpty()) {
                        JSONObject first = candidates.getJSONObject(0);
                        finishReason.set(toFinishReason(first.optString("finishReason", ""), false));
                        JSONObject content = first.optJSONObject("content");
                        if (content != null) {
                            JSONArray parts = content.optJSONArray("parts");
                            if (parts != null) {
                                for (int i = 0; i < parts.length(); i++) {
                                    JSONObject p = parts.getJSONObject(i);
                                    if (p.has("text")) {
                                        String delta = p.getString("text");
                                        fullText.append(delta);
                                        if (onDelta != null) {
                                            onDelta.accept(delta);
                                        }
                                    } else if (p.has("functionCall")) {
                                        JSONObject fc = p.getJSONObject("functionCall");
                                        String name = fc.optString("name", "");
                                        JSONObject args = fc.optJSONObject("args");
                                        String argsJson = args != null ? args.toString() : "{}";
                                        allToolCalls.add(new ToolCall(UUID.randomUUID().toString(), name, argsJson));
                                    }
                                }
                            }
                        }
                    }
                }
            });

            Message assistantMessage = new Message(Role.ASSISTANT, fullText.toString(), null);
            return new ChatResponse(assistantMessage, allToolCalls,
                    toFinishReason(finishReason.get(), !allToolCalls.isEmpty()), usage.get());

        } catch (IOException e) {
            throw new RuntimeException("Failed to call Gemini streaming", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to call Gemini streaming", e);
        }
    }

    private JSONObject buildRequestBody(List<Message> messages, List<ToolSchema> tools) {
        JSONObject requestBody = new JSONObject();
        JSONArray contents = new JSONArray();

        for (Message m : messages) {
            JSONObject c = new JSONObject();
            String geminiRole = (m.getRole() == Role.ASSISTANT) ? "model" : "user";
            c.put("role", geminiRole);

            JSONArray parts = new JSONArray();

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
                if (!m.getContent().isEmpty()) {
                    JSONObject textPart = new JSONObject();
                    textPart.put("text", m.getContent());
                    parts.put(textPart);
                }

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
            if (parts.isEmpty()) {
                JSONObject emptyText = new JSONObject();
                emptyText.put("text", " ");
                parts.put(emptyText);
            }

            c.put("parts", parts);
            contents.put(c);
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
                    fn.put("parameters", new JSONObject(ts.getJsonSchema()));
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

    @Override
    public ChatResponse chat(List<Message> messages, List<ToolSchema> tools) {

        try {
            JSONObject requestBody = buildRequestBody(messages, tools);

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

            JSONObject body = new JSONObject(response.body());
            JSONArray candidates = body.optJSONArray("candidates");
            if (candidates == null || candidates.isEmpty()) {
                throw new RuntimeException("Gemini returned no candidates. Body: " + response.body());
            }

            JSONObject first = candidates.getJSONObject(0);
            JSONObject content = first.optJSONObject("content");
            String finishReason = first.optString("finishReason", "");
            ModelUsage usage = parseUsage(body);

            List<ToolCall> toolCalls = new ArrayList<>();
            StringBuilder assistantText = new StringBuilder();

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
                            toolCalls.add(new ToolCall(UUID.randomUUID().toString(), name, argsJson));
                        } else if (p.has("text")) {
                            assistantText.append(p.optString("text", ""));
                        }
                    }
                }
            }

            Message assistantMessage = new Message(Role.ASSISTANT, assistantText.toString(), null);
            return new ChatResponse(assistantMessage, toolCalls,
                    toFinishReason(finishReason, !toolCalls.isEmpty()), usage);

        } catch (IOException e) {
            throw new RuntimeException("Failed to call Gemini", e);
        } catch (InterruptedException e) {
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
        return switch (reason == null ? "" : reason.toUpperCase(java.util.Locale.ROOT)) {
            case "STOP" -> FinishReason.STOP;
            case "MAX_TOKENS", "RECITATION" -> FinishReason.LENGTH;
            case "SAFETY", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII" ->
                    FinishReason.CONTENT_FILTER;
            case "MALFORMED_FUNCTION_CALL" -> FinishReason.TOOL_CALLS;
            default -> FinishReason.UNKNOWN;
        };
    }

    private static FinishReason toFinishReason(FinishReason current, boolean hasToolCalls) {
        return hasToolCalls ? FinishReason.TOOL_CALLS : current;
    }
}
