package ai.lambda.ai.client;

import ai.lambda.ai.core.Modality;
import ai.lambda.ai.core.ModelCapabilities;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * A provider that speaks the OpenAI Chat Completions protocol, with what makes it different:
 * its address, how it wants media encoded, and what its models accept.
 *
 * <p>Presets: {@link #OPENAI}, {@link #OPENROUTER}, {@link #XAI}, {@link #MISTRAL},
 * {@link #EXPERIENTIAL_LABS}, {@link #OLLAMA}, {@link #OLLAMA_CLOUD} and {@link #PERPLEXITY_ROUTER}.
 * Use {@link #custom} for any other compatible server, such as vLLM, LM Studio or a company gateway.
 * Perplexity's main API uses a different protocol: see {@code ResponsesModelClient}.
 *
 * @param name           display name, used in error messages
 * @param baseUrl        the API root, ending in {@code /v1} (requests go to {@code baseUrl + "/chat/completions"})
 * @param requiresApiKey whether a key must be given
 * @param dialect        how media and tool results are encoded
 * @param capabilities   what a model accepts, given its name
 * @param streamUsage    whether to ask for token usage in streamed responses
 * @param headers        extra headers sent with every request
 */
public record OpenAICompatibleProvider(String name, String baseUrl, boolean requiresApiKey, Dialect dialect,
                                       Function<String, ModelCapabilities> capabilities, boolean streamUsage,
                                       Map<String, String> headers) {

    /** How a provider encodes media parts and tool results. */
    public enum Dialect {
        /**
         * OpenAI's own format: images as {@code image_url}, audio as base64 {@code input_audio}
         * (wav/mp3), PDFs as base64 {@code file}. No video.
         */
        OPENAI,
        /** OpenAI's format plus {@code video_url}, URLs for PDFs, and more audio formats. */
        OPENROUTER,
        /**
         * Mistral's format: images, documents ({@code document_url}) and audio as plain strings
         * (a URL or data URL), tool results with a name.
         */
        MISTRAL,
        /** xAI: OpenAI's chat format; image generation takes an aspect ratio and resolution. */
        XAI
    }

    public OpenAICompatibleProvider {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        Objects.requireNonNull(dialect, "dialect must not be null");
        Objects.requireNonNull(capabilities, "capabilities must not be null");
        baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    private static boolean has(String model, String... words) {
        String m = model.toLowerCase(Locale.ROOT);
        for (String w : words) if (m.contains(w)) return true;
        return false;
    }

    /**
     * OpenAI. Images (files or URLs) and PDFs (files) on current models; audio input only on
     * the audio models (names containing "audio"). No video input.
     */
    public static final OpenAICompatibleProvider OPENAI = new OpenAICompatibleProvider(
            "OpenAI", "https://api.openai.com/v1", true, Dialect.OPENAI,
            model -> has(model, "audio")
                    ? ModelCapabilities.of(Modality.IMAGE, Modality.AUDIO, Modality.DOCUMENT).withMediaUrls(Modality.IMAGE)
                    : ModelCapabilities.of(Modality.IMAGE, Modality.DOCUMENT).withMediaUrls(Modality.IMAGE),
            true, Map.of());

    /**
     * OpenRouter, which routes to many providers' models. Accepts images, PDFs, audio and video
     * (what actually works depends on the chosen model); audio must be sent as bytes.
     */
    public static final OpenAICompatibleProvider OPENROUTER = new OpenAICompatibleProvider(
            "OpenRouter", "https://openrouter.ai/api/v1", true, Dialect.OPENROUTER,
            model -> ModelCapabilities.of(Modality.IMAGE, Modality.AUDIO, Modality.VIDEO, Modality.DOCUMENT)
                    .withMediaUrls(Modality.IMAGE, Modality.VIDEO, Modality.DOCUMENT),
            true, Map.of());

    /**
     * Experiential Labs, an OpenAI-compatible gateway to many models. Its documentation says audio
     * is refused and does not describe other media, so images are assumed (the common case for
     * the models it routes to); override the capabilities if your model differs.
     */
    public static final OpenAICompatibleProvider EXPERIENTIAL_LABS = new OpenAICompatibleProvider(
            "Experiential Labs", "https://api.experientiallabs.ai/v1", true, Dialect.OPENAI,
            model -> ModelCapabilities.of(Modality.IMAGE).withMediaUrls(Modality.IMAGE),
            true, Map.of());

    /**
     * xAI (Grok). Its Chat Completions API takes images (JPEG or PNG, files or URLs); PDFs, audio
     * and video are not supported there.
     */
    public static final OpenAICompatibleProvider XAI = new OpenAICompatibleProvider(
            "xAI", "https://api.x.ai/v1", true, Dialect.XAI,
            model -> ModelCapabilities.of(Modality.IMAGE).withMediaUrls(Modality.IMAGE),
            true, Map.of());

    /**
     * Mistral. Images and PDFs (files or URLs) on vision models; audio (files or URLs) on the
     * Voxtral models (names containing "voxtral").
     */
    public static final OpenAICompatibleProvider MISTRAL = new OpenAICompatibleProvider(
            "Mistral", "https://api.mistral.ai/v1", true, Dialect.MISTRAL,
            model -> has(model, "voxtral")
                    ? ModelCapabilities.of(Modality.AUDIO).withMediaUrls(Modality.AUDIO)
                    : ModelCapabilities.of(Modality.IMAGE, Modality.DOCUMENT).withMediaUrls(Modality.IMAGE, Modality.DOCUMENT),
            true, Map.of());

    /**
     * Ollama on this machine ({@code http://localhost:11434}); no API key. Vision models take
     * images as files only (Ollama rejects image URLs). For Ollama's cloud, use
     * {@link #OLLAMA_CLOUD} with an API key.
     */
    public static final OpenAICompatibleProvider OLLAMA = new OpenAICompatibleProvider(
            "Ollama", "http://localhost:11434/v1", false, Dialect.OPENAI,
            model -> ModelCapabilities.of(Modality.IMAGE), true, Map.of());

    /** Ollama's hosted service ({@code https://ollama.com}), which needs an API key. */
    public static final OpenAICompatibleProvider OLLAMA_CLOUD = new OpenAICompatibleProvider(
            "Ollama Cloud", "https://ollama.com/v1", true, Dialect.OPENAI,
            model -> ModelCapabilities.of(Modality.IMAGE), true, Map.of());

    /**
     * Perplexity's Router API (Chat Completions; private preview, request access from Perplexity).
     * For Perplexity's main Agent API use {@code ResponsesModelClient.perplexity(...)}.
     */
    public static final OpenAICompatibleProvider PERPLEXITY_ROUTER = new OpenAICompatibleProvider(
            "Perplexity Router", "https://api.perplexity.ai/router/v1", true, Dialect.OPENAI,
            model -> ModelCapabilities.of(Modality.IMAGE).withMediaUrls(Modality.IMAGE),
            false, Map.of()); // the Router rejects fields it does not know, such as stream_options

    /** Any other OpenAI-compatible server, assumed to accept images and to support tools. */
    public static OpenAICompatibleProvider custom(String name, String baseUrl) {
        return new OpenAICompatibleProvider(name, baseUrl, false, Dialect.OPENAI,
                model -> ModelCapabilities.of(Modality.IMAGE).withMediaUrls(Modality.IMAGE), true, Map.of());
    }

    /** A copy with a different API root, for example a proxy or a self-hosted instance. */
    public OpenAICompatibleProvider withBaseUrl(String baseUrl) {
        return new OpenAICompatibleProvider(name, baseUrl, requiresApiKey, dialect, capabilities, streamUsage, headers);
    }

    /** A copy that sends these headers too (for example OpenRouter's {@code HTTP-Referer}). */
    public OpenAICompatibleProvider withHeaders(Map<String, String> headers) {
        Map<String, String> all = new java.util.LinkedHashMap<>(this.headers);
        all.putAll(headers);
        return new OpenAICompatibleProvider(name, baseUrl, requiresApiKey, dialect, capabilities, streamUsage, all);
    }
}
