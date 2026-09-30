package ai.lambda.ai.core;

public final class ToolCall {

    private final String id;
    private final String name;
    private final String argumentsJson;
    private final String signature;

    public ToolCall(String id, String name, String argumentsJson) {
        this(id, name, argumentsJson, null);
    }

    /**
     * @param signature opaque token the provider attached to this call (Gemini's
     *                  {@code thoughtSignature}). It must be sent back unchanged when
     *                  this call is replayed in history. May be null.
     */
    public ToolCall(String id, String name, String argumentsJson, String signature) {
        this.id = id;
        this.name = name;
        this.argumentsJson = argumentsJson;
        this.signature = signature;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getArgumentsJson() {
        return argumentsJson;
    }

    public String getSignature() {
        return signature;
    }
}
