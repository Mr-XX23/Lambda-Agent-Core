package ai.lambda.agent.core;

public final class AgentResult {

    private final String finalText;
    private final AgentSession session;

    public AgentResult(String finalText, AgentSession session) {
        this.finalText = finalText;
        this.session = session;
    }

    public String getFinalText() {
        return finalText;
    }

    public AgentSession getSession() {
        return session;
    }
}
