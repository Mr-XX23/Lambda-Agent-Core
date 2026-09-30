package ai.lambda.ai.openai;

import ai.lambda.ai.core.HttpOptions;
import ai.lambda.ai.core.Media;
import ai.lambda.ai.generation.ImageGenerator;
import ai.lambda.ai.generation.ImageRequest;
import com.openai.client.OpenAIClient;
import com.openai.models.images.Image;
import com.openai.models.images.ImageEditParams;
import com.openai.models.images.ImageGenerateParams;
import com.openai.models.images.ImagesResponse;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Image generation with OpenAI's image models, through OpenAI's official Java SDK.
 *
 * <pre>
 * var images = new OpenAIImageGenerator(key, "gpt-image-2");
 * Media logo = images.generateImage("A minimalist fox logo");
 * Media edited = images.generateImages(ImageRequest.of("Make the sky purple")
 *         .withReferenceImages(Media.fromFile(Path.of("photo.png")))).get(0);   // one image to edit
 * </pre>
 */
public final class OpenAIImageGenerator implements ImageGenerator {

    private final OpenAIClient client;
    private final String model;

    public OpenAIImageGenerator(String apiKey, String model) {
        this(apiKey, model, HttpOptions.defaults(), null);
    }

    /** @param baseUrl another API root, or null for OpenAI's own */
    public OpenAIImageGenerator(String apiKey, String model, HttpOptions options, String baseUrl) {
        this(OpenAIClients.create(apiKey, options, baseUrl), model);
    }

    public OpenAIImageGenerator(OpenAIClient client, String model) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.model = Objects.requireNonNull(model, "model must not be null");
    }

    /**
     * Creates images from the prompt, or edits one reference image. {@code size} is a size such
     * as {@code "1024x1024"}; options are extra request fields such as {@code quality}.
     */
    @Override
    public List<Media> generateImages(ImageRequest request) {
        ImagesResponse response = request.referenceImages().isEmpty() ? generate(request) : edit(request);
        List<Media> images = new ArrayList<>();
        String format = response.outputFormat().map(f -> f.asString()).orElse("png");
        String type = "image/" + (format.equals("jpg") ? "jpeg" : format);
        for (Image image : response.data().orElse(List.of())) {
            if (image.b64Json().isPresent()) {
                images.add(Media.fromBase64(image.b64Json().get(), type));
            } else if (image.url().isPresent()) {
                images.add(download(image.url().get(), type)); // some models return short-lived URLs
            }
        }
        if (images.isEmpty()) throw new RuntimeException("OpenAI returned no images");
        return images;
    }

    private ImagesResponse generate(ImageRequest request) {
        ImageGenerateParams.Builder params = ImageGenerateParams.builder()
                .model(model).prompt(request.prompt()).n(request.count());
        if (request.size() != null) params.size(request.size());
        OpenAIClients.putOptions(request.options(), params::putAdditionalBodyProperty);
        return client.images().generate(params.build());
    }

    private ImagesResponse edit(ImageRequest request) {
        if (request.referenceImages().size() > 1) {
            throw new UnsupportedOperationException("OpenAI image edits take one reference image here");
        }
        Media reference = request.referenceImages().get(0);
        if (!reference.hasData()) throw new IllegalArgumentException("Load the reference image with Media.fromFile");
        String fileName = reference.name() != null ? reference.name() : "image." + reference.fileExtension();
        ImageEditParams.Builder params = ImageEditParams.builder()
                .model(model).prompt(request.prompt()).n(request.count())
                .image(OpenAIClients.file(ImageEditParams.Image.ofInputStream(OpenAIClients.stream(reference.data())),
                        fileName, reference.mimeType()));
        if (request.size() != null) params.size(request.size());
        OpenAIClients.putOptions(request.options(), params::putAdditionalBodyProperty);
        return client.images().edit(params.build());
    }

    private static Media download(String url, String fallbackType) {
        try (HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()) {
            HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(URI.create(url)).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 400) throw new RuntimeException("Could not download the image: " + response.statusCode());
            String type = response.headers().firstValue("Content-Type").filter(t -> t.startsWith("image/"))
                    .map(t -> t.split(";")[0].trim()).orElse(fallbackType);
            return Media.of(response.body(), type);
        } catch (IOException e) {
            throw new RuntimeException("Could not download the image from " + url, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while downloading the image", e);
        }
    }
}
