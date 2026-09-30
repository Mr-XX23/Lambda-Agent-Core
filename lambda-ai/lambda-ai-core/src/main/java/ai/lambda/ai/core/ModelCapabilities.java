package ai.lambda.ai.core;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * What a model client can do: which kinds of media it accepts as input, whether it can call
 * tools, and whether it can fetch media from URLs. Clients check messages against this before
 * sending, so an unsupported file fails with a clear message instead of a provider error.
 *
 * <p>Capabilities depend on the model as well as the provider (a text-only model rejects images
 * even where the provider supports them), so clients let you override their defaults.
 *
 * @param input      accepted input kinds; always includes {@link Modality#TEXT}
 * @param toolCalling whether the model can call tools
 * @param mediaUrls  input kinds that may be given as URLs rather than bytes
 */
public record ModelCapabilities(Set<Modality> input, boolean toolCalling, Set<Modality> mediaUrls) {

    public ModelCapabilities {
        EnumSet<Modality> in = EnumSet.of(Modality.TEXT);
        in.addAll(Objects.requireNonNull(input, "input must not be null"));
        input = Set.copyOf(in);
        mediaUrls = Set.copyOf(mediaUrls == null ? Set.of() : mediaUrls);
    }

    public static ModelCapabilities textOnly() {
        return new ModelCapabilities(Set.of(), true, Set.of());
    }

    public static ModelCapabilities of(Modality... input) {
        return new ModelCapabilities(Set.of(input), true, Set.of());
    }

    public ModelCapabilities withToolCalling(boolean toolCalling) {
        return new ModelCapabilities(input, toolCalling, mediaUrls);
    }

    public ModelCapabilities withMediaUrls(Modality... kinds) {
        return new ModelCapabilities(input, toolCalling, Set.of(kinds));
    }

    public boolean accepts(Modality modality) {
        return input.contains(modality);
    }

    /**
     * Throws {@link UnsupportedMediaException} if any message carries media this model cannot
     * take, or tools are given to a model that cannot call them.
     */
    public void check(List<Message> messages, List<ToolSchema> tools, String provider, String model) {
        if (tools != null && !tools.isEmpty() && !toolCalling) {
            throw new UnsupportedMediaException(provider + " model '" + model + "' cannot call tools, but "
                    + tools.size() + " tool(s) were given");
        }
        for (Message message : messages) {
            for (Media media : message.getMedia()) {
                Modality kind = media.modality();
                if (!accepts(kind)) {
                    throw new UnsupportedMediaException(provider + " model '" + model + "' does not accept "
                            + kind.name().toLowerCase() + " input (" + media.mimeType() + "). It accepts: "
                            + describe(input));
                }
                if (!media.hasData() && !mediaUrls.contains(kind)) {
                    throw new UnsupportedMediaException(provider + " cannot fetch " + kind.name().toLowerCase()
                            + " from a URL (" + media.url() + "); load the file with Media.fromFile or Media.of instead");
                }
            }
        }
    }

    private static String describe(Set<Modality> kinds) {
        return kinds.stream().map(k -> k.name().toLowerCase()).sorted().reduce((a, b) -> a + ", " + b).orElse("text");
    }
}
