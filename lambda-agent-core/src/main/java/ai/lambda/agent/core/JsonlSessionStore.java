package ai.lambda.agent.core;

import ai.lambda.ai.core.Message;
import org.json.JSONObject;

import java.util.List;
import java.io.*;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class JsonlSessionStore implements SessionStore {

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

        try (BufferedReader reader = Files.newBufferedReader(sessionFile)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                JSONObject obj = new JSONObject(line);
                session.getMessages().add(Message.fromJson(obj));
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

        // 1. Save Messages
        try {
            StringBuilder content = new StringBuilder();
            for (Message msg : session.getMessages()) {
                content.append(msg.toJson()).append(System.lineSeparator());
            }
            atomicWrite(sessionFile, content.toString());
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

    private void atomicWrite(Path target, String content) throws IOException {
        Path temporary = Files.createTempFile(storageDir, "." + target.getFileName(), ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE);
                 BufferedWriter writer = new BufferedWriter(
                         Channels.newWriter(channel, StandardCharsets.UTF_8))) {
                writer.write(content);
                writer.flush();
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
