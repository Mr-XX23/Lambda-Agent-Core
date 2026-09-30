package ai.lambda.agent.core;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CancellationToken {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final CancellationToken parent;

    public CancellationToken() {
        this(null);
    }

    private CancellationToken(CancellationToken parent) {
        this.parent = parent;
    }

    /**
     * A token that is cancelled when this one is, and can also be cancelled on its own without
     * affecting this one. Use it to give part of the work its own way to stop.
     */
    public CancellationToken child() {
        return new CancellationToken(this);
    }

    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get() || (parent != null && parent.isCancelled());
    }

    public void throwIfCancelled() {
        if (isCancelled()) {
            throw new CancellationException("Agent run was cancelled");
        }
    }
}
