package ai.lambda.ai.core;

/** A request uses media or features the model does not support; the message says which. */
public final class UnsupportedMediaException extends IllegalArgumentException {
    public UnsupportedMediaException(String message) {
        super(message);
    }
}
