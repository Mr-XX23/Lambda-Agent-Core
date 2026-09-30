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
import java.util.Objects;

/**
 * Image generation through the OpenAI Images API ({@code POST /images/generations}), used by
 * OpenAI (for example {@code gpt-image-2}) and xAI (for example {@code grok-imagine-image-2.0}).
 * For xAI, {@link ImageRequest#size()} is an aspect ratio ({@code "16:9"}) or a resolution
 * ({@code "1k"}, {@code "2k"}).
 *
 * <pre>
 * var images = new OpenAICompatibleImageGenerator(OpenAICompatibleProvider.OPENAI, key, "gpt-image-2");
 * images.generateImage("A lighthouse at dawn, watercolor").saveTo(Path.of("lighthouse.png"));
 * </pre>
 *
 * For OpenAI, {@link ImageRequest#size()} is sent as {@code size} (for example {@code "1536x1024"}).
 * {@link ImageRequest#options()} can add fields such as {@code quality} or {@code output_format}.
 * Reference images are not supported by this endpoint.
 */
public final class OpenAICompatibleImageGenerator implements ImageGenerator {

    private final OpenAICompatibleProvider provider;
    private final String model;
    private final JsonHttp http;

    public OpenAICompatibleImageGenerator(OpenAICompatibleProvider provider, String apiKey, String model) {
        this(provider, apiKey, model, HttpOptions.defaults());
    }

    public OpenAICompatibleImageGenerator(OpenAICompatibleProvider provider, String apiKey, String model, HttpOptions options) {
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
        this.model = Objects.requireNonNull(model, "model must not be null");
        java.util.Map<String, String> headers = JsonHttp.bearer(apiKey);
        headers.putAll(provider.headers());
        this.http = new JsonHttp(provider.name(), options, headers);
    }

    @Override
    public List<Media> generateImages(ImageRequest request) {
        if (!request.referenceImages().isEmpty()) {
            throw new UnsupportedOperationException(provider.name()
                    + " image generation here takes a text prompt only; reference images are not supported");
        }
        JSONObject body = new JSONObject().put("model", model).put("prompt", request.prompt()).put("n", request.count());
        if (request.size() != null) {
            if (provider.dialect() == OpenAICompatibleProvider.Dialect.XAI) {
                // xAI: "16:9" is an aspect ratio, "1k" / "2k" a resolution.
                body.put(request.size().contains(":") ? "aspect_ratio" : "resolution", request.size().toLowerCase());
            } else {
                body.put("size", request.size());
            }
        }
        if (provider.dialect() == OpenAICompatibleProvider.Dialect.XAI) body.put("response_format", "b64_json");
        request.options().forEach(body::put);

        JSONObject response = http.postJson(URI.create(provider.baseUrl() + "/images/generations"), body);
        String format = response.optString("output_format", body.optString("output_format", "png"));
        List<Media> images = new ArrayList<>();
        JSONArray data = response.getJSONArray("data");
        for (int i = 0; i < data.length(); i++) {
            JSONObject item = data.getJSONObject(i);
            String type = item.optString("mime_type", "image/" + (format.equals("jpg") ? "jpeg" : format));
            if (item.has("b64_json") && !item.isNull("b64_json")) {
                images.add(Media.fromBase64(item.getString("b64_json"), type));
            } else if (item.has("url")) {
                // Some models return short-lived URLs instead; fetch the bytes now.
                JsonHttp.Binary image = http.download(URI.create(item.getString("url")));
                images.add(Media.of(image.data(), image.contentType().startsWith("image/") ? image.contentType() : type));
            }
        }
        if (images.isEmpty()) throw new RuntimeException(provider.name() + " returned no images: " + response);
        return images;
    }
}
