package ai.lambda.agent.prebuilt;

import ai.lambda.agent.core.AgentSession;
import ai.lambda.agent.core.AgentTool;
import ai.lambda.agent.core.ToolCapability;
import ai.lambda.agent.core.ToolInvocationContext;
import ai.lambda.ai.core.Media;
import ai.lambda.ai.generation.ImageRequest;
import ai.lambda.ai.generation.SpeechRequest;
import ai.lambda.ai.generation.VideoRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MediaToolsTest {

    @TempDir
    Path dir;

    private static String run(AgentTool tool, String args) throws Exception {
        tool.getArgumentValidator().validate(args);
        return tool.execute(new ToolInvocationContext("c1", args, new AgentSession("s"))).getContent();
    }

    private static List<Path> savedPaths(String result) {
        String list = result.substring(result.indexOf(": ") + 2);
        return java.util.Arrays.stream(list.split(", ")).map(Path::of).toList();
    }

    @Test
    void generateImageSavesEveryImageAndPassesTheOptions() throws Exception {
        List<ImageRequest> seen = new ArrayList<>();
        AgentTool tool = MediaTools.generateImage(request -> {
            seen.add(request);
            return List.of(Media.of(new byte[]{1}, "image/png"), Media.of(new byte[]{2}, "image/png"));
        }, dir.resolve("out"));

        String result = run(tool, "{\"prompt\":\"a fox\",\"size\":\"16:9\",\"count\":9}");

        assertTrue(result.startsWith("Saved 2 file(s): "), result);
        for (Path path : savedPaths(result)) {
            assertTrue(Files.exists(path), path.toString());
            assertTrue(path.toString().endsWith(".png"));
        }
        assertEquals("16:9", seen.get(0).size());
        assertEquals(4, seen.get(0).count(), "count is capped at 4");
        assertTrue(tool.getCapabilities().contains(ToolCapability.WRITE));
    }

    @Test
    void generateSpeechAndVideo() throws Exception {
        List<SpeechRequest> speech = new ArrayList<>();
        List<VideoRequest> video = new ArrayList<>();
        AgentTool tts = MediaTools.generateSpeech(r -> { speech.add(r); return Media.of(new byte[]{3}, "audio/mpeg"); }, dir);
        AgentTool veo = MediaTools.generateVideo(r -> { video.add(r); return Media.of(new byte[]{4}, "video/mp4"); }, dir);

        String audioResult = run(tts, "{\"text\":\"hello\",\"voice\":\"coral\",\"instructions\":\"warm\"}");
        String videoResult = run(veo, "{\"prompt\":\"waves\",\"seconds\":8,\"size\":\"720p\"}");

        assertTrue(savedPaths(audioResult).get(0).toString().endsWith(".mp3"));
        assertTrue(savedPaths(videoResult).get(0).toString().endsWith(".mp4"));
        assertEquals("coral", speech.get(0).voice());
        assertEquals("warm", speech.get(0).instructions());
        assertEquals(8, video.get(0).seconds());
        assertEquals("720p", video.get(0).size());
        assertTrue(veo.getPolicy().timeout().toMinutes() >= 10, "video generation needs a long timeout");
    }

    @Test
    void transcribeReadsOnlyInsideTheWorkspace() throws Exception {
        Files.write(dir.resolve("memo.wav"), new byte[]{1, 2});
        Files.write(dir.getParent().resolve("outside-" + dir.getFileName() + ".wav"), new byte[]{1});
        List<String> languages = new ArrayList<>();
        AgentTool tool = MediaTools.transcribeAudio((audio, language) -> {
            languages.add(language);
            return "heard " + audio.size() + " bytes of " + audio.mimeType();
        }, dir);

        assertEquals("heard 2 bytes of audio/wav", run(tool, "{\"path\":\"memo.wav\",\"language\":\"en\"}"));
        assertEquals(List.of("en"), languages);
        assertThrows(SecurityException.class,
                () -> run(tool, "{\"path\":\"../outside-" + dir.getFileName() + ".wav\"}"));
        assertThrows(IllegalArgumentException.class, () -> run(tool, "{\"path\":\"missing.wav\"}"));
    }

    @Test
    void requiredArgumentsAreChecked() {
        AgentTool tool = MediaTools.generateImage(request -> List.of(), dir);
        assertThrows(IllegalArgumentException.class, () -> tool.getArgumentValidator().validate("{\"size\":\"1:1\"}"));
    }
}
