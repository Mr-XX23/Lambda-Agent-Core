package ai.lambda.agent.core;

import ai.lambda.ai.core.Media;
import ai.lambda.ai.core.Message;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The stored form of session messages and metadata, shared by the session stores. A message's
 * media bytes are not stored inline: each is kept once under a name made from its SHA-256 (for
 * example {@code 3f2a...9c.jpg}) and the message refers to it by that name.
 */
final class SessionJson {

    static final Pattern MEDIA_NAME = Pattern.compile("[0-9a-f]{64}\\.[a-z0-9]{1,10}");

    /** Reads the bytes stored under a media name. */
    interface MediaReader {
        byte[] read(String name) throws IOException;
    }

    private SessionJson() {
    }

    static String mediaName(Media media) {
        return media.sha256() + "." + media.fileExtension();
    }

    /** The message as JSON, with media replaced by references; the referenced media are added to {@code media}. */
    static JSONObject encode(Message message, Map<String, Media> media) {
        if (message.getMedia().isEmpty()) return message.toJson();
        JSONObject json = message.withMedia(List.of()).toJson();
        JSONArray refs = new JSONArray();
        for (Media item : message.getMedia()) {
            if (!item.hasData()) {
                refs.put(item.toJson()); // a URL: nothing to store
                continue;
            }
            String name = mediaName(item);
            media.putIfAbsent(name, item);
            JSONObject ref = new JSONObject().put("mimeType", item.mimeType()).put("file", name);
            if (item.name() != null) ref.put("name", item.name());
            refs.put(ref);
        }
        return json.put("media", refs);
    }

    /** Reads a message written by {@link #encode}, or by older versions that stored media inline. */
    static Message decode(JSONObject json, MediaReader reader) throws IOException {
        JSONArray refs = (JSONArray) json.remove("media");
        Message message = Message.fromJson(json);
        if (refs == null) return message;
        List<Media> media = new ArrayList<>();
        for (int i = 0; i < refs.length(); i++) {
            JSONObject ref = refs.getJSONObject(i);
            if (!ref.has("file")) {
                media.add(Media.fromJson(ref)); // a URL, or inline base64 from older versions
                continue;
            }
            String name = ref.getString("file");
            if (!MEDIA_NAME.matcher(name).matches()) throw new IOException("Invalid media name: " + name);
            Media item = Media.of(reader.read(name), ref.getString("mimeType"));
            media.add(ref.has("name") ? item.withName(ref.getString("name")) : item);
        }
        return message.withMedia(media);
    }

    static String encodeMetadata(Map<String, Object> metadata) {
        return new JSONObject(metadata).toString();
    }

    /** Reads metadata into {@code target}; top-level arrays become lists. */
    static void decodeMetadata(String json, Map<String, Object> target) {
        if (json == null || json.isBlank()) return;
        JSONObject object = new JSONObject(json);
        for (String key : object.keySet()) {
            Object value = object.get(key);
            if (value instanceof JSONArray array) {
                List<Object> list = new ArrayList<>();
                for (int i = 0; i < array.length(); i++) list.add(array.get(i));
                target.put(key, list);
            } else {
                target.put(key, value);
            }
        }
    }
}
