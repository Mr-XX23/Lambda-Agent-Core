package ai.lambda.agent.core;

import java.util.LinkedHashMap;
import java.util.Map;

public final class WorkflowContext {
    private final Map<String, Object> state;

    public WorkflowContext(Map<String, Object> initialState) {
        this.state = new LinkedHashMap<>();
        if (initialState != null) {
            this.state.putAll(initialState);
        }
    }

    public synchronized Map<String, Object> getState() {
        return state;
    }

    public synchronized Object get(String key) {
        return state.get(key);
    }

    public synchronized void put(String key, Object value) {
        state.put(key, value);
    }
}
