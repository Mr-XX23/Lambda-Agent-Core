package ai.lambda.ai.client;

import ai.lambda.ai.core.HttpOptions;
import ai.lambda.ai.core.Media;
import ai.lambda.ai.generation.ImageGenerator;
import ai.lambda.ai.generation.ImageRequest;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Image generation on OpenRouter with image-capable chat models (for example
 * {@code google/gemini-3.1-flash-image}), which return images alongside text.
 * {@link ImageRequest#size()} may be an aspect ratio such as {@code "16:9"}; reference images
 * are sent with the prompt for editing.
 */
public final class OpenRouterImageGenerator implements ImageGenerator {

    private final OpenAICompatibleProvider provider;
    private final String model;
    private final JsonHttp http;

    public OpenRouterImageGenerator(String apiKey, String model) {
        this(OpenAICompatibleProvider.OPENROUTER, apiKey, model, HttpOptions.defaults());
    }

    OpenRouterImageGenerator(OpenAICompatibleProvider provider, String apiKey, String model, HttpOptions options) {
        this.provider = provider;
        this.model = Objects.requireNonNull(model, "model must not be null");
        Map<String, String> headers = JsonHttp.bearer(apiKey);
        headers.putAll(provider.headers());
        this.http = new JsonHttp(provider.name(), options, headers);
    }

    @Override
    public List<Media> generateImages(ImageRequest request) {
        JSONArray content = new JSONArray().put(new JSONObject().put("type", "text").put("text", request.prompt()));
        for (Media image : request.referenceImages()) {
            content.put(new JSONObject().put("type", "image_url").put("image_url", new JSONObject().put("url", image.dataUrl())));
        }
        JSONObject body = new JSONObject()
                .put("model", model)
                .put("stream", false) // images are not delivered in streamed responses
                .put("modalities", new JSONArray().put("image").put("text"))
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", content)));
        if (request.size() != null) {
            if (!request.size().contains(":")) {
                throw new IllegalArgumentException("OpenRouter image size must be an aspect ratio such as 16:9");
            }
            body.put("image_config", new JSONObject().put("aspect_ratio", request.size()));
        }
        request.options().forEach(body::put);

        List<Media> images = new ArrayList<>();
        for (int i = 0; i < request.count(); i++) {
            JSONObject response = http.postJson(URI.create(provider.baseUrl() + "/chat/completions"), body);
            JSONObject message = response.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
            JSONArray returned = message.optJSONArray("images");
            if (returned == null || returned.isEmpty()) {
                throw new RuntimeException("OpenRouter model '" + model + "' returned no image: "
                        + message.optString("content", ""));
            }
            for (int j = 0; j < returned.length(); j++) {
                images.add(fromUrl(returned.getJSONObject(j).getJSONObject("image_url").getString("url")));
            }
        }
        return images;
    }

    private Media fromUrl(String url) {
        if (url.startsWith("data:")) {
            int comma = url.indexOf(',');
            String type = url.substring(5, url.indexOf(';'));
            return Media.fromBase64(url.substring(comma + 1), type);
        }
        JsonHttp.Binary image = http.download(URI.create(url));
        return Media.of(image.data(), image.contentType().startsWith("image/") ? image.contentType() : "image/png");
    }
}
