package ai.lambda.agent.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemorySessionStore implements SessionStore {
    private final Map<String, AgentSession> sessions = new ConcurrentHashMap<>();

    @Override
    public AgentSession loadOrCreate(String sessionId) {
        return sessions.computeIfAbsent(sessionId, AgentSession::new);
    }

    @Override
    public void save(AgentSession session) {
        sessions.put(session.getId(), session);
    }
}
