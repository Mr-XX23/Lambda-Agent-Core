package ai.lambda.ai.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Shares provider SDK clients between model clients and generators with the same settings.
 * An SDK client owns a connection pool and threads: sharing one keeps connections warm and
 * stops every {@code new OpenAIModelClient(...)} (for example one per request or per subagent)
 * from starting its own. For provider modules; applications do not need it.
 *
 * <p>At most {@value #MAX_CLIENTS} clients are kept, least recently used first out. A client that
 * drops out is not closed, since model clients may still use it; its idle connections and
 * threads end on their own. API keys are kept only as a SHA-256, not as text.
 */
public final class ProviderClients {

    static final int MAX_CLIENTS = 64;

    private record Key(String provider, String apiKeyHash, HttpOptions options, String baseUrl) {
    }

    private static final Map<Key, Object> CLIENTS = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, Object> eldest) {
            return size() > MAX_CLIENTS;
        }
    };

    private ProviderClients() {
    }

    /**
     * The shared client for these settings, created with {@code create} the first time.
     *
     * @param provider a name that keeps different providers' clients apart, such as {@code "openai"}
     * @param baseUrl  the API root, or null for the provider's own
     */
    @SuppressWarnings("unchecked")
    public static <T> T shared(String provider, String apiKey, HttpOptions options, String baseUrl, Supplier<T> create) {
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(create, "create must not be null");
        Key key = new Key(provider, apiKey == null ? null : sha256(apiKey), options, baseUrl);
        synchronized (CLIENTS) {
            Object client = CLIENTS.get(key);
            if (client == null) {
                client = Objects.requireNonNull(create.get(), "create returned null");
                CLIENTS.put(key, client);
            }
            return (T) client;
        }
    }

    static int size() {
        synchronized (CLIENTS) {
            return CLIENTS.size();
        }
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
