package ai.lambda.ai.core;

/** A kind of content a model can take in or produce. */
public enum Modality {
    TEXT,
    IMAGE,
    AUDIO,
    VIDEO,
    /** PDFs and other documents. */
    DOCUMENT;

    /** The modality of a MIME type, such as {@code image/png} or {@code application/pdf}. */
    public static Modality ofMimeType(String mimeType) {
        String type = mimeType == null ? "" : mimeType.toLowerCase(java.util.Locale.ROOT);
        if (type.startsWith("image/")) return IMAGE;
        if (type.startsWith("audio/")) return AUDIO;
        if (type.startsWith("video/")) return VIDEO;
        if (type.equals("application/pdf") || type.startsWith("text/")) return DOCUMENT;
        throw new IllegalArgumentException("Unsupported media type '" + mimeType
                + "'; expected image/*, audio/*, video/*, application/pdf or text/*");
    }
}
