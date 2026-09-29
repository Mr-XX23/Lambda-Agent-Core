package ai.lambda.ai.core;

import org.json.JSONObject;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * An image, audio clip, video or document, sent to a model or produced by one. It holds
 * either the bytes or a URL the provider can fetch.
 *
 * <pre>
 * Media photo = Media.fromFile(Path.of("receipt.jpg"));
 * Media report = Media.fromUrl("https://example.com/report.pdf", "application/pdf");
 * agent.run("s1", "What is the total on this receipt?", photo);
 * </pre>
 */
public final class Media {

    private static final Map<String, String> MIME_BY_EXTENSION = Map.ofEntries(
            Map.entry("png", "image/png"), Map.entry("jpg", "image/jpeg"), Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"), Map.entry("webp", "image/webp"), Map.entry("heic", "image/heic"),
            Map.entry("heif", "image/heif"), Map.entry("bmp", "image/bmp"),
            Map.entry("mp3", "audio/mpeg"), Map.entry("wav", "audio/wav"), Map.entry("m4a", "audio/mp4"),
            Map.entry("aac", "audio/aac"), Map.entry("ogg", "audio/ogg"), Map.entry("oga", "audio/ogg"),
            Map.entry("opus", "audio/opus"), Map.entry("flac", "audio/flac"), Map.entry("aiff", "audio/aiff"),
            Map.entry("mp4", "video/mp4"), Map.entry("mov", "video/quicktime"), Map.entry("webm", "video/webm"),
            Map.entry("avi", "video/x-msvideo"), Map.entry("mkv", "video/x-matroska"), Map.entry("mpeg", "video/mpeg"),
            Map.entry("mpg", "video/mpeg"), Map.entry("3gp", "video/3gpp"),
            Map.entry("pdf", "application/pdf"), Map.entry("txt", "text/plain"), Map.entry("md", "text/markdown"),
            Map.entry("csv", "text/csv"), Map.entry("html", "text/html"), Map.entry("htm", "text/html"));

    private static final Map<String, String> EXTENSION_BY_MIME = Map.ofEntries(
            Map.entry("image/png", "png"), Map.entry("image/jpeg", "jpg"), Map.entry("image/gif", "gif"),
            Map.entry("image/webp", "webp"), Map.entry("image/heic", "heic"), Map.entry("image/heif", "heif"),
            Map.entry("image/bmp", "bmp"), Map.entry("audio/mpeg", "mp3"), Map.entry("audio/mp3", "mp3"),
            Map.entry("audio/wav", "wav"), Map.entry("audio/x-wav", "wav"), Map.entry("audio/mp4", "m4a"),
            Map.entry("audio/aac", "aac"), Map.entry("audio/ogg", "ogg"), Map.entry("audio/opus", "opus"),
            Map.entry("audio/flac", "flac"), Map.entry("audio/aiff", "aiff"), Map.entry("audio/pcm", "pcm"),
            Map.entry("video/mp4", "mp4"), Map.entry("video/quicktime", "mov"), Map.entry("video/webm", "webm"),
            Map.entry("video/x-msvideo", "avi"), Map.entry("video/x-matroska", "mkv"), Map.entry("video/mpeg", "mpeg"),
            Map.entry("video/3gpp", "3gp"), Map.entry("application/pdf", "pdf"), Map.entry("text/plain", "txt"),
            Map.entry("text/markdown", "md"), Map.entry("text/csv", "csv"), Map.entry("text/html", "html"));

    private final String mimeType;
    private final byte[] data;
    private final String url;
    private final String name;
    // Encoded once: the same media is sent again on every model call of a run.
    private volatile String base64;

    private Media(String mimeType, byte[] data, String url, String name) {
        this.mimeType = Objects.requireNonNull(mimeType, "mimeType must not be null").toLowerCase(Locale.ROOT);
        Modality.ofMimeType(this.mimeType); // rejects unsupported types early
        if ((data == null) == (url == null)) throw new IllegalArgumentException("Media needs either data or a URL");
        this.data = data;
        this.url = url;
        this.name = name;
    }

    /** Media from bytes. */
    public static Media of(byte[] data, String mimeType) {
        return new Media(mimeType, Objects.requireNonNull(data, "data must not be null").clone(), null, null);
    }

    /** Media from base64-encoded bytes. */
    public static Media fromBase64(String base64, String mimeType) {
        return new Media(mimeType, Base64.getDecoder().decode(base64), null, null);
    }

    /** Reads a file; its type is detected from the extension (for example .png, .mp3, .mp4, .pdf). */
    public static Media fromFile(Path file) {
        String fileName = file.getFileName().toString();
        try {
            return new Media(mimeTypeOf(file), Files.readAllBytes(file), null, fileName);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    /**
     * Media the provider fetches from a URL. Not every provider accepts URLs for every kind of
     * media; clients report that clearly. Use {@link #fromFile} to send the bytes instead.
     */
    public static Media fromUrl(String url, String mimeType) {
        URI.create(Objects.requireNonNull(url, "url must not be null"));
        return new Media(mimeType, null, url, null);
    }

    /** Media from a URL, with the type guessed from the URL's file extension. */
    public static Media fromUrl(String url) {
        String path = URI.create(url).getPath();
        String type = path == null ? null : MIME_BY_EXTENSION.get(extension(path));
        if (type == null) throw new IllegalArgumentException("Cannot tell the media type of " + url + "; pass it explicitly");
        return fromUrl(url, type);
    }

    /** A copy with a file name, which some providers show to the model. */
    public Media withName(String name) {
        return new Media(mimeType, data, url, name);
    }

    static String mimeTypeOf(Path file) {
        String type = MIME_BY_EXTENSION.get(extension(file.getFileName().toString()));
        if (type != null) return type;
        try {
            type = Files.probeContentType(file);
        } catch (IOException ignored) {
            // fall through
        }
        if (type == null) throw new IllegalArgumentException("Cannot tell the media type of " + file);
        return type;
    }

    private static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public Modality modality() {
        return Modality.ofMimeType(mimeType);
    }

    public String mimeType() {
        return mimeType;
    }

    public boolean hasData() {
        return data != null;
    }

    /** The bytes (a copy), or null for URL media. */
    public byte[] data() {
        return data == null ? null : data.clone();
    }

    /** The bytes as base64, or null for URL media. */
    public String base64() {
        if (data == null) return null;
        String encoded = base64;
        if (encoded == null) base64 = encoded = Base64.getEncoder().encodeToString(data);
        return encoded;
    }

    /** A {@code data:} URL holding the bytes, or the plain URL for URL media. */
    public String dataUrl() {
        return data == null ? url : "data:" + mimeType + ";base64," + base64();
    }

    public String url() {
        return url;
    }

    public String name() {
        return name;
    }

    /** Size in bytes, or -1 for URL media. */
    public long size() {
        return data == null ? -1 : data.length;
    }

    /** Writes the bytes to a file (for generated images, audio and video). */
    public Path saveTo(Path file) {
        if (data == null) throw new IllegalStateException("This media is a URL (" + url + "); there are no bytes to save");
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            return Files.write(file, data);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + file, e);
        }
    }

    /** A file extension for this type, such as {@code png}, or {@code bin} if unknown. */
    public String fileExtension() {
        return EXTENSION_BY_MIME.getOrDefault(mimeType, "bin");
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject().put("mimeType", mimeType);
        if (data != null) json.put("data", base64());
        if (url != null) json.put("url", url);
        if (name != null) json.put("name", name);
        return json;
    }

    public static Media fromJson(JSONObject json) {
        String type = json.getString("mimeType");
        Media media = json.has("data") ? fromBase64(json.getString("data"), type) : fromUrl(json.getString("url"), type);
        return json.has("name") ? media.withName(json.getString("name")) : media;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Media other && mimeType.equals(other.mimeType) && Arrays.equals(data, other.data)
                && Objects.equals(url, other.url) && Objects.equals(name, other.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mimeType, Arrays.hashCode(data), url, name);
    }

    @Override
    public String toString() {
        return "Media{" + mimeType + ", " + (data != null ? data.length + " bytes" : url) + (name != null ? ", " + name : "") + "}";
    }
}
