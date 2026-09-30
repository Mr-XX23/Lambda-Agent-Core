package ai.lambda.ai.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Downloads a file a provider pointed to, such as a generated image or video, safely:
 * <ul>
 *   <li>no API key or other credentials are sent: the URL comes from the provider's response and
 *       may be a storage or CDN host;</li>
 *   <li>only https (plain http only to this machine, for local servers and tests);</li>
 *   <li>at most {@link #MAX_BYTES} by default, checked while reading, so a huge file cannot fill memory;</li>
 *   <li>a connect timeout, a wait for the first byte, and a stop if the transfer stalls.</li>
 * </ul>
 * For provider modules; applications do not need it.
 */
public final class Downloads {

    /** The largest file downloaded unless a caller allows more. */
    public static final long MAX_BYTES = 512L * 1024 * 1024;

    private static final int MAX_ERROR_CHARS = 2_000;
    private static final Map<Duration, HttpClient> CLIENTS = new ConcurrentHashMap<>();

    private Downloads() {
    }

    /** A downloaded file. */
    public record File(byte[] data, String contentType) {
    }

    public static File fetch(String provider, URI uri, HttpOptions options) {
        return fetch(provider, uri, options, MAX_BYTES);
    }

    public static File fetch(String provider, URI uri, HttpOptions options, long maxBytes) {
        requireSafe(uri);
        // One shared client per connect timeout; redirects to other storage hosts are followed
        // (the JDK drops credentials on such redirects and never goes from https to http).
        HttpClient client = CLIENTS.computeIfAbsent(options.connectTimeout(), timeout -> HttpClient.newBuilder()
                .connectTimeout(timeout).followRedirects(HttpClient.Redirect.NORMAL).build());
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(options.requestTimeout()).GET().build();
        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new UncheckedIOException(provider + " download from " + uri.getHost() + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while downloading from " + uri.getHost(), e);
        }
        try (InputStream body = response.body();
             StreamWatchdog watchdog = new StreamWatchdog(provider, options.requestTimeout(), body)) {
            if (response.statusCode() >= 400) {
                // The URL often carries a signature, so only the host goes into the message.
                throw new RuntimeException(provider + " download from " + uri.getHost() + " failed: " + response.statusCode()
                        + " " + new String(body.readNBytes(MAX_ERROR_CHARS), StandardCharsets.UTF_8));
            }
            long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (declared > maxBytes) throw tooLarge(provider, declared, maxBytes);
            ByteArrayOutputStream data = new ByteArrayOutputStream(declared > 0 ? (int) declared : 64 * 1024);
            byte[] buffer = new byte[64 * 1024];
            try {
                for (int read; (read = body.read(buffer)) != -1; ) {
                    watchdog.activity();
                    if (data.size() + (long) read > maxBytes) throw tooLarge(provider, data.size() + (long) read, maxBytes);
                    data.write(buffer, 0, read);
                }
            } catch (IOException failure) {
                throw watchdog.explain(failure);
            }
            watchdog.check();
            String type = response.headers().firstValue("Content-Type").orElse("application/octet-stream");
            return new File(data.toByteArray(), type.split(";")[0].trim().toLowerCase(Locale.ROOT));
        } catch (IOException e) {
            throw new UncheckedIOException(provider + " download from " + uri.getHost() + " failed", e);
        }
    }

    private static RuntimeException tooLarge(String provider, long bytes, long maxBytes) {
        return new RuntimeException(provider + " download is larger than " + maxBytes + " bytes (" + bytes + "); refused");
    }

    /** Throws unless the URL uses https, or plain http to this machine. */
    public static void requireSafe(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (scheme.equals("https") && uri.getHost() != null) return;
        if (scheme.equals("http") && isLoopback(uri.getHost())) return;
        throw new SecurityException("Refusing to download from " + uri.getScheme() + "://" + uri.getHost()
                + ": provider files must come over https");
    }

    private static boolean isLoopback(String host) {
        if (host == null) return false;
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
