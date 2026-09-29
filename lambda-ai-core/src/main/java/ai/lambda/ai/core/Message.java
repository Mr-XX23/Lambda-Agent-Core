package ai.lambda.ai.core;

import java.util.ArrayList;
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
    private final List<Media> media;
    private final ProviderState providerState;

    public Message(Role role, String content, String toolCallId, String toolCallName, List<ToolCall> toolCalls) {
        this(role, content, toolCallId, toolCallName, toolCalls, List.of(), null);
    }

    public Message(Role role, String content, String toolCallId) {
        this(role, content, toolCallId, null, List.of(), List.of(), null);
    }

    private Message(Role role, String content, String toolCallId, String toolCallName, List<ToolCall> toolCalls,
                    List<Media> media, ProviderState providerState) {
        this.role = Objects.requireNonNull(role, "role must not be null");
        this.content = content == null ? "" : content;
        this.toolCallId = toolCallId;
        this.toolCallName = toolCallName;
        this.toolCalls = toolCalls == null ? Collections.emptyList() : List.copyOf(toolCalls);
        this.media = media == null ? Collections.emptyList() : List.copyOf(media);
        this.providerState = providerState;
    }

    /** A user message with text and attached images, audio, video or documents. */
    public static Message user(String text, Media... media) {
        return new Message(Role.USER, text, null, null, List.of(), List.of(media), null);
    }

    /** A copy with these attachments. */
    public Message withMedia(List<Media> media) {
        return new Message(role, content, toolCallId, toolCallName, toolCalls, media, providerState);
    }

    /** A copy carrying provider data that must be sent back unchanged (see {@link ProviderState}). */
    public Message withProviderState(ProviderState providerState) {
        return new Message(role, content, toolCallId, toolCallName, toolCalls, media, providerState);
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

    /** Attached images, audio, video and documents (empty if none). */
    public List<Media> getMedia() {
        return media;
    }

    /** Provider data to send back unchanged, or null. */
    public ProviderState getProviderState() {
        return providerState;
    }

    @Override
    public String toString() {
        return "Message{" +
                "role=" + role +
                ", content='" + content + '\'' +
                ", toolCallId='" + toolCallId + '\'' +
                (media.isEmpty() ? "" : ", media=" + media) +
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
                if (tc.getSignature() != null) tObj.put("signature", tc.getSignature());
                tcArray.put(tObj);
            }
            obj.put("toolCalls", tcArray);
        }
        if (!media.isEmpty()) {
            JSONArray mediaArray = new JSONArray();
            for (Media m : media) mediaArray.put(m.toJson());
            obj.put("media", mediaArray);
        }
        if (providerState != null) {
            obj.put("providerState", new JSONObject()
                    .put("provider", providerState.provider())
                    .put("json", providerState.json()));
        }
        return obj;
    }

    public static Message fromJson(JSONObject obj) {
        Role role = Role.valueOf(obj.getString("role"));
        String content = obj.optString("content", "");
        String toolCallId = obj.optString("toolCallId", null);
        String toolCallName = obj.optString("toolCallName", null);

        List<ToolCall> tcs = new ArrayList<>();
        JSONArray tcArray = obj.optJSONArray("toolCalls");
        if (tcArray != null) {
            for (int i = 0; i < tcArray.length(); i++) {
                JSONObject tObj = tcArray.getJSONObject(i);
                tcs.add(new ToolCall(
                        tObj.getString("id"),
                        tObj.getString("name"),
                        tObj.getString("argumentsJson"),
                        tObj.optString("signature", null)
                ));
            }
        }
        List<Media> media = new ArrayList<>();
        JSONArray mediaArray = obj.optJSONArray("media");
        if (mediaArray != null) {
            for (int i = 0; i < mediaArray.length(); i++) media.add(Media.fromJson(mediaArray.getJSONObject(i)));
        }
        JSONObject state = obj.optJSONObject("providerState");
        ProviderState providerState = state == null ? null
                : new ProviderState(state.getString("provider"), state.getString("json"));
        return new Message(role, content, toolCallId, toolCallName, tcs, media, providerState);
    }
}
