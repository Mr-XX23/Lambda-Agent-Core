package ai.lambda.agent.core;

import ai.lambda.ai.core.Message;
import org.json.JSONObject;

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
        try (BufferedWriter writer = Files.newBufferedWriter(sessionFile,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {

            for (Message msg : session.getMessages()) {
                writer.write(msg.toJson().toString());
                writer.newLine();
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to save session " + session.getId(), e);
        }
    }
}
