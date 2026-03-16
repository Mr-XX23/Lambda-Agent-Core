package ai.lambda.ai.core;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.json.JSONArray;
import org.json.JSONObject;

public final class Message {
    private final Role role;
    private final String content;
    private final String toolCallId;
    private final String toolCallName;
    private final List<ToolCall> toolCalls;

    public Message(Role role, String content, String toolCallId, String toolCallName, List<ToolCall> toolCalls) {
        this.role = Objects.requireNonNull(role, "role must not be null");
        this.content = content == null ? "" : content;
        this.toolCallId = toolCallId;
        this.toolCallName = toolCallName;
        this.toolCalls = toolCalls == null ? Collections.emptyList() : List.copyOf(toolCalls);
    }

    public Message(Role role, String content, String toolCallId) {
        this.role = Objects.requireNonNull(role, "role must not be null");
        this.content = content == null ? "" : content;
        this.toolCallId = toolCallId;
        this.toolCallName = null;
        this.toolCalls = Collections.emptyList();
    }

    public Role getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public String getToolCallName() {
        return toolCallName;
    }

    public List<ToolCall> getToolCalls() {
        return toolCalls;
    }

    @Override
    public String toString() {
        return "Message{" +
                "role=" + role +
                ", content='" + content + '\'' +
                ", toolCallId='" + toolCallId + '\'' +
                '}';
    }

    public JSONObject toJson() {
        JSONObject obj = new JSONObject();
        obj.put("role", role.name());
        obj.put("content", content);
        if (toolCallId != null) obj.put("toolCallId", toolCallId);
        if (toolCallName != null) obj.put("toolCallName", toolCallName);

        if (!toolCalls.isEmpty()) {
            JSONArray tcArray = new JSONArray();
            for (ToolCall tc : toolCalls) {
                JSONObject tObj = new JSONObject();
                tObj.put("id", tc.getId());
                tObj.put("name", tc.getName());
                tObj.put("argumentsJson", tc.getArgumentsJson());
                tcArray.put(tObj);
            }
            obj.put("toolCalls", tcArray);
        }
        return obj;
    }

    public static Message fromJson(JSONObject obj) {
        Role role = Role.valueOf(obj.getString("role"));
        String content = obj.optString("content", "");
        String toolCallId = obj.optString("toolCallId", null);
        String toolCallName = obj.optString("toolCallName", null);

        List<ToolCall> tcs = new java.util.ArrayList<>();
        JSONArray tcArray = obj.optJSONArray("toolCalls");
        if (tcArray != null) {
            for (int i = 0; i < tcArray.length(); i++) {
                JSONObject tObj = tcArray.getJSONObject(i);
                tcs.add(new ToolCall(
                        tObj.getString("id"),
                        tObj.getString("name"),
                        tObj.getString("argumentsJson")
                ));
            }
        }
        return new Message(role, content, toolCallId, toolCallName, tcs);
    }
}
