package ai.lambda.agent.core;

public final class AgentResult {

    private final String finalText;
    private final AgentSession session;
    private final String runId;
    private final int iterations;

    public AgentResult(String finalText, AgentSession session) {
        this(finalText, session, java.util.UUID.randomUUID().toString(), 0);
    }

    public AgentResult(String finalText, AgentSession session, String runId, int iterations) {
        this.finalText = finalText;
        this.session = session;
        this.runId = runId;
        this.iterations = iterations;
    }

    public String getFinalText() {
        return finalText;
    }

    public AgentSession getSession() {
        return session;
    }

    public String getRunId() {
        return runId;
    }

    public int getIterations() {
        return iterations;
    }
}
