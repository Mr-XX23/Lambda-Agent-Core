package ai.lambda.agent.core;

public interface SessionStore {

    AgentSession loadOrCreate(String sessionId);

    void save(AgentSession session);
}
