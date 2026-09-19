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

    public Map<String, Object> getState() {
        return state;
    }

    public Object get(String key) {
        return state.get(key);
    }

    public void put(String key, Object value) {
        state.put(key, value);
    }
}
