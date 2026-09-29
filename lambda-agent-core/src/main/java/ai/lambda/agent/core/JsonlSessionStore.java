package ai.lambda.agent.core;

import ai.lambda.ai.core.Media;
import ai.lambda.ai.core.Message;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Stores each session as files in one directory:
 * <ul>
 *   <li>{@code <id>.jsonl}: the messages, one JSON object per line</li>
 *   <li>{@code <id>.meta.json}: the session metadata (tool state)</li>
 *   <li>{@code <id>.media/}: attached images, audio, video and documents, one file per distinct
 *       content, named by its SHA-256 (for example {@code 3f2a...9c.jpg})</li>
 * </ul>
 * Messages refer to their media by file name, so the JSONL stays small and a photo is written
 * once, not re-encoded as base64 on every save. Sessions saved by older versions, with the
 * media inline as base64, still load and are converted on the next save.
 */
public final class JsonlSessionStore implements SessionStore {

    private static final Pattern MEDIA_FILE = Pattern.compile("[0-9a-f]{64}\\.[a-z0-9]{1,10}");

    private final Path storageDir;

    public JsonlSessionStore(Path storageDir) {
        this.storageDir = Objects.requireNonNull(storageDir, "storageDir must not be null")
                .toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.storageDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create session directory", e);
        }
    }

    @Override
    public AgentSession loadOrCreate(String sessionId) {
        validateSessionId(sessionId);
        AgentSession session = new AgentSession(sessionId);
        Path sessionFile = storageDir.resolve(sessionId + ".jsonl");
        Path metaFile = storageDir.resolve(sessionId + ".meta.json");

        // 1. Load Metadata (Tool State)
        if (Files.exists(metaFile)) {
            try {
                String content = Files.readString(metaFile);
                if (!content.isBlank()) {
                    JSONObject metaObj = new JSONObject(content);
                    for (String key : metaObj.keySet()) {
                        Object value = metaObj.get(key);
                        // Convert JSONArray to List for easier Java usage
                        if (value instanceof org.json.JSONArray array) {
                            List<Object> list = new java.util.ArrayList<>();
                            for (int i = 0; i < array.length(); i++) {
                                list.add(array.get(i));
                            }
                            session.getMetadata().put(key, list);
                        } else {
                            session.getMetadata().put(key, value);
                        }
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to load metadata for session " + sessionId, e);
            }
        }

        // 2. Load Messages (History)
        if (!Files.exists(sessionFile)) {
            return session;
        }

        Path mediaDir = mediaDir(sessionId);
        try (BufferedReader reader = Files.newBufferedReader(sessionFile)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                JSONObject obj = new JSONObject(line);
                JSONArray mediaArray = (JSONArray) obj.remove("media");
                Message message = Message.fromJson(obj);
                if (mediaArray != null) {
                    List<Media> media = new ArrayList<>();
                    for (int i = 0; i < mediaArray.length(); i++) {
                        media.add(readMedia(mediaArray.getJSONObject(i), mediaDir, sessionId));
                    }
                    message = message.withMedia(media);
                }
                session.getMessages().add(message);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load session " + sessionId, e);
        }
        return session;
    }

    @Override
    public void save(AgentSession session) {
        Objects.requireNonNull(session, "session must not be null");
        validateSessionId(session.getId());
        Path sessionFile = storageDir.resolve(session.getId() + ".jsonl");
        Path metaFile = storageDir.resolve(session.getId() + ".meta.json");

        // 1. Save Messages, with media as separate files
        try {
            Path mediaDir = mediaDir(session.getId());
            Set<String> mediaFiles = new HashSet<>();
            StringBuilder content = new StringBuilder();
            for (Message msg : session.getMessages()) {
                if (msg.getMedia().isEmpty()) {
                    content.append(msg.toJson()).append(System.lineSeparator());
                    continue;
                }
                JSONObject obj = msg.withMedia(List.of()).toJson();
                JSONArray mediaArray = new JSONArray();
                for (Media media : msg.getMedia()) {
                    mediaArray.put(writeMedia(media, mediaDir, mediaFiles));
                }
                obj.put("media", mediaArray);
                content.append(obj).append(System.lineSeparator());
            }
            atomicWrite(sessionFile, content.toString());
            // Only after the messages are safely written: they no longer refer to these files.
            deleteUnusedMedia(mediaDir, mediaFiles);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save session " + session.getId(), e);
        }

        // 2. Save Metadata (Tool State)
        if (!session.getMetadata().isEmpty()) {
            try {
                JSONObject metaObj = new JSONObject(session.getMetadata());
                atomicWrite(metaFile, metaObj.toString(2));
            } catch (Exception e) {
                throw new RuntimeException("Failed to save metadata for session " + session.getId(), e);
            }
        }
    }

    private Path mediaDir(String sessionId) {
        return storageDir.resolve(sessionId + ".media");
    }

    /** Writes the media's bytes unless an identical file is already stored; returns its JSON reference. */
    private static JSONObject writeMedia(Media media, Path mediaDir, Set<String> mediaFiles) throws IOException {
        if (!media.hasData()) return media.toJson(); // a URL: nothing to store
        String fileName = media.sha256() + "." + media.fileExtension();
        if (mediaFiles.add(fileName)) {
            Path file = mediaDir.resolve(fileName);
            if (!Files.exists(file)) {
                Files.createDirectories(mediaDir);
                atomicWrite(file, media.data());
            }
        }
        JSONObject ref = new JSONObject().put("mimeType", media.mimeType()).put("file", fileName);
        if (media.name() != null) ref.put("name", media.name());
        return ref;
    }

    private static Media readMedia(JSONObject json, Path mediaDir, String sessionId) throws IOException {
        if (!json.has("file")) return Media.fromJson(json); // a URL, or inline base64 from older versions
        String fileName = json.getString("file");
        if (!MEDIA_FILE.matcher(fileName).matches()) {
            throw new IOException("Invalid media file name in session " + sessionId + ": " + fileName);
        }
        Path file = mediaDir.resolve(fileName);
        if (!Files.exists(file)) {
            throw new IOException("Media file missing for session " + sessionId + ": " + file);
        }
        Media media = Media.of(Files.readAllBytes(file), json.getString("mimeType"));
        return json.has("name") ? media.withName(json.getString("name")) : media;
    }

    private static void deleteUnusedMedia(Path mediaDir, Set<String> used) throws IOException {
        if (!Files.isDirectory(mediaDir)) return;
        try (Stream<Path> files = Files.list(mediaDir)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                // Files this store did not write are left alone.
                if (MEDIA_FILE.matcher(name).matches() && !used.contains(name)) Files.deleteIfExists(file);
            }
        }
        if (used.isEmpty()) {
            try {
                Files.deleteIfExists(mediaDir);
            } catch (DirectoryNotEmptyException ignored) {
                // it holds files this store did not write
            }
        }
    }

    private static void atomicWrite(Path target, String content) throws IOException {
        atomicWrite(target, content.getBytes(StandardCharsets.UTF_8));
    }

    private static void atomicWrite(Path target, byte[] content) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), "." + target.getFileName(), ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(content);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void validateSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()
                || !sessionId.matches("[A-Za-z0-9._-]+")
                || sessionId.equals(".") || sessionId.equals("..")) {
            throw new IllegalArgumentException("Invalid session id: " + sessionId);
        }
    }
}
