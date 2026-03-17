package ai.lambda.agent.core;

import ai.lambda.ai.core.Message;
import org.json.JSONObject;

import java.util.List;
import java.io.*;
import java.nio.file.*;

public final class JsonlSessionStore implements SessionStore {

    private final Path storageDir;

    public JsonlSessionStore(Path storageDir) {
        this.storageDir = storageDir;
        try {
            Files.createDirectories(storageDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create session directory", e);
        }
    }

    @Override
    public AgentSession loadOrCreate(String sessionId) {
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
            } catch (IOException e) {
                // Log and continue, metadata is secondary to conversation
                System.err.println("[lambda-agent-core] Failed to load metadata for session " + sessionId + ": " + e.getMessage());
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
        } catch (IOException e) {
            throw new RuntimeException("Failed to load session " + sessionId, e);
        }
        return session;
    }

    @Override
    public void save(AgentSession session) {
        Path sessionFile = storageDir.resolve(session.getId() + ".jsonl");
        Path metaFile = storageDir.resolve(session.getId() + ".meta.json");

        // 1. Save Messages
        try (BufferedWriter writer = Files.newBufferedWriter(sessionFile,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {

            for (Message msg : session.getMessages()) {
                writer.write(msg.toJson().toString());
                writer.newLine();
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to save session " + session.getId(), e);
        }

        // 2. Save Metadata (Tool State)
        if (!session.getMetadata().isEmpty()) {
            try {
                JSONObject metaObj = new JSONObject(session.getMetadata());
                Files.writeString(metaFile, metaObj.toString(2));
            } catch (IOException e) {
                System.err.println("[lambda-agent-core] Failed to save metadata for session " + session.getId() + ": " + e.getMessage());
            }
        }
    }
}
