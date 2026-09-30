package ai.lambda.agent.core;

import ai.lambda.ai.core.Media;
import ai.lambda.ai.core.Message;
import ai.lambda.ai.core.ProviderState;
import ai.lambda.ai.core.Role;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class JsonlSessionStoreMediaTest {

    @TempDir
    Path storageDir;

    private static final byte[] PHOTO = bytes(50_000, 7);
    private static final byte[] CLIP = bytes(20_000, 3);

    private static byte[] bytes(int size, int seed) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) data[i] = (byte) (i * seed);
        return data;
    }

    private List<Path> mediaFiles(String sessionId) throws IOException {
        Path dir = storageDir.resolve(sessionId + ".media");
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files.sorted().toList();
        }
    }

    @Test
    void mediaIsStoredAsFilesAndRoundTrips() throws IOException {
        JsonlSessionStore store = new JsonlSessionStore(storageDir);
        AgentSession session = new AgentSession("s");
        Media photo = Media.of(PHOTO, "image/jpeg").withName("receipt.jpg");
        session.getMessages().add(Message.user("what is this?", photo, Media.of(CLIP, "audio/mpeg")));
        session.getMessages().add(new Message(Role.ASSISTANT, "a receipt", null)
                .withProviderState(new ProviderState("anthropic", "[{\"type\":\"thinking\"}]")));

        store.save(session);

        String jsonl = Files.readString(storageDir.resolve("s.jsonl"));
        assertFalse(jsonl.contains("\"data\""), "bytes must not be inline");
        assertTrue(jsonl.length() < 2_000, "JSONL holds only references: " + jsonl.length());
        assertEquals(Set.of(photo.sha256() + ".jpg", Media.of(CLIP, "audio/mpeg").sha256() + ".mp3"),
                mediaFiles("s").stream().map(f -> f.getFileName().toString()).collect(Collectors.toSet()));
        assertArrayEquals(PHOTO, Files.readAllBytes(storageDir.resolve("s.media").resolve(photo.sha256() + ".jpg")));

        AgentSession loaded = new JsonlSessionStore(storageDir).loadOrCreate("s");
        Message user = loaded.getMessages().get(0);
        assertEquals("what is this?", user.getContent());
        assertEquals(List.of(photo, Media.of(CLIP, "audio/mpeg")), user.getMedia());
        assertEquals("receipt.jpg", user.getMedia().get(0).name());
        assertEquals("anthropic", loaded.getMessages().get(1).getProviderState().provider());
    }

    @Test
    void identicalMediaIsStoredOnce() throws IOException {
        AgentSession session = new AgentSession("dup");
        session.getMessages().add(Message.user("one", Media.of(PHOTO, "image/png").withName("a.png")));
        session.getMessages().add(Message.user("two", Media.of(PHOTO, "image/png").withName("b.png")));

        new JsonlSessionStore(storageDir).save(session);

        assertEquals(1, mediaFiles("dup").size());
        AgentSession loaded = new JsonlSessionStore(storageDir).loadOrCreate("dup");
        assertEquals("a.png", loaded.getMessages().get(0).getMedia().get(0).name());
        assertEquals("b.png", loaded.getMessages().get(1).getMedia().get(0).name());
        assertArrayEquals(PHOTO, loaded.getMessages().get(1).getMedia().get(0).data());
    }

    @Test
    void laterSavesDoNotRewriteStoredMedia() throws IOException {
        JsonlSessionStore store = new JsonlSessionStore(storageDir);
        AgentSession session = new AgentSession("again");
        session.getMessages().add(Message.user("look", Media.of(PHOTO, "image/jpeg")));
        store.save(session);
        Path file = mediaFiles("again").get(0);
        FileTime marker = FileTime.fromMillis(1_000_000_000_000L);
        Files.setLastModifiedTime(file, marker);

        session.getMessages().add(new Message(Role.ASSISTANT, "nice", null));
        store.save(session);

        assertEquals(marker, Files.getLastModifiedTime(file));
    }

    @Test
    void mediaNoLongerReferencedIsDeleted() throws IOException {
        JsonlSessionStore store = new JsonlSessionStore(storageDir);
        AgentSession session = new AgentSession("trim");
        session.getMessages().add(Message.user("old", Media.of(PHOTO, "image/jpeg")));
        session.getMessages().add(Message.user("new", Media.of(CLIP, "audio/wav")));
        store.save(session);
        Path unrelated = storageDir.resolve("trim.media").resolve("notes.txt");
        Files.writeString(unrelated, "kept");

        session.getMessages().remove(0);
        store.save(session);

        List<Path> files = mediaFiles("trim");
        assertEquals(2, files.size(), files.toString());
        assertTrue(files.contains(unrelated), "files the store did not write are left alone");
        assertTrue(files.stream().anyMatch(f -> f.toString().endsWith(".wav")));

        Files.delete(unrelated);
        session.getMessages().clear();
        store.save(session);
        assertFalse(Files.exists(storageDir.resolve("trim.media")), "empty media folder is removed");
    }

    @Test
    void urlMediaStaysAUrl() throws IOException {
        AgentSession session = new AgentSession("url");
        Media report = Media.fromUrl("https://example.com/report.pdf");
        session.getMessages().add(Message.user("summarize", report));

        new JsonlSessionStore(storageDir).save(session);

        assertTrue(mediaFiles("url").isEmpty());
        assertEquals(List.of(report), new JsonlSessionStore(storageDir).loadOrCreate("url").getMessages().get(0).getMedia());
    }

    @Test
    void inlineMediaFromOlderVersionsLoadsAndMovesToFilesOnSave() throws IOException {
        // What earlier versions wrote: the bytes inline as base64.
        Media photo = Media.of(PHOTO, "image/jpeg").withName("old.jpg");
        Files.writeString(storageDir.resolve("legacy.jsonl"),
                Message.user("from before", photo).toJson() + System.lineSeparator());
        JsonlSessionStore store = new JsonlSessionStore(storageDir);

        AgentSession loaded = store.loadOrCreate("legacy");
        assertEquals(List.of(photo), loaded.getMessages().get(0).getMedia());

        store.save(loaded);
        assertFalse(Files.readString(storageDir.resolve("legacy.jsonl")).contains("\"data\""));
        assertEquals(1, mediaFiles("legacy").size());
        assertEquals(List.of(photo), store.loadOrCreate("legacy").getMessages().get(0).getMedia());
    }

    @Test
    void rejectsMediaReferencesOutsideTheMediaFolder() throws IOException {
        Files.writeString(storageDir.resolve("secret.txt"), "private");
        JSONObject line = new Message(Role.USER, "hi", null).toJson().put("media", new JSONArray()
                .put(new JSONObject().put("mimeType", "text/plain").put("file", "../secret.txt")));
        Files.writeString(storageDir.resolve("bad.jsonl"), line.toString());

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> new JsonlSessionStore(storageDir).loadOrCreate("bad"));
        assertTrue(error.getCause().getMessage().contains("Invalid media name"), error.getCause().getMessage());
    }

    @Test
    void missingMediaFileIsReportedClearly() throws IOException {
        JsonlSessionStore store = new JsonlSessionStore(storageDir);
        AgentSession session = new AgentSession("gone");
        session.getMessages().add(Message.user("look", Media.of(PHOTO, "image/jpeg")));
        store.save(session);
        Files.delete(mediaFiles("gone").get(0));

        RuntimeException error = assertThrows(RuntimeException.class, () -> store.loadOrCreate("gone"));
        assertTrue(error.getCause().getMessage().contains("Media file missing for session gone"),
                error.getCause().getMessage());
    }
}
