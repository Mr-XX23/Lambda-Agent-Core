package ai.lambda.agent.core;

/** Thrown when a checkpoint or session was saved by someone else after it was loaded. */
public final class OptimisticLockException extends RuntimeException {
    public OptimisticLockException(String executionId, long expected, long actual) {
        super("Checkpoint '" + executionId + "' changed: expected version " + expected
                + ", actual version " + actual);
    }

    public OptimisticLockException(String message) {
        super(message);
    }
}
