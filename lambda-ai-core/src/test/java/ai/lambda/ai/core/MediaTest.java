package ai.lambda.ai.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MediaTest {

    @TempDir
    Path dir;

    @Test
    void detectsTypeAndModalityFromTheFileName() throws Exception {
        Path png = Files.write(dir.resolve("photo.PNG"), new byte[]{1, 2, 3});
        Path mp3 = Files.write(dir.resolve("voice.mp3"), new byte[]{4});
        Path mp4 = Files.write(dir.resolve("clip.mp4"), new byte[]{5});
        Path pdf = Files.write(dir.resolve("report.pdf"), new byte[]{6});

        assertEquals(Modality.IMAGE, Media.fromFile(png).modality());
        assertEquals("image/png", Media.fromFile(png).mimeType());
        assertEquals(Modality.AUDIO, Media.fromFile(mp3).modality());
        assertEquals(Modality.VIDEO, Media.fromFile(mp4).modality());
        assertEquals(Modality.DOCUMENT, Media.fromFile(pdf).modality());
        assertEquals("photo.PNG", Media.fromFile(png).name());
    }

    @Test
    void holdsBytesOrAUrl() {
        Media bytes = Media.of(new byte[]{'h', 'i'}, "text/plain");
        Media url = Media.fromUrl("https://example.com/cat.jpg");

        assertEquals("aGk=", bytes.base64());
        assertEquals("data:text/plain;base64,aGk=", bytes.dataUrl());
        assertEquals(2, bytes.size());
        assertFalse(url.hasData());
        assertEquals("image/jpeg", url.mimeType());
        assertEquals("https://example.com/cat.jpg", url.dataUrl());
    }

    @Test
    void bytesCannotBeChangedFromOutside() {
        byte[] original = {1, 2};
        Media media = Media.of(original, "image/png");
        original[0] = 9;
        media.data()[1] = 9;

        assertArrayEquals(new byte[]{1, 2}, media.data());
    }

    @Test
    void rejectsUnknownTypes() {
        assertThrows(IllegalArgumentException.class, () -> Media.of(new byte[1], "application/zip"));
        assertThrows(IllegalArgumentException.class, () -> Media.fromUrl("https://example.com/file"));
    }

    @Test
    void jsonRoundTripAndSaving() {
        Media media = Media.of(new byte[]{7, 8, 9}, "audio/wav").withName("beep.wav");

        assertEquals(media, Media.fromJson(media.toJson()));
        Path saved = media.saveTo(dir.resolve("out/beep." + media.fileExtension()));
        assertTrue(saved.toString().endsWith("beep.wav"));
        assertThrows(IllegalStateException.class, () -> Media.fromUrl("https://x.io/a.png").saveTo(dir.resolve("a.png")));
    }

    @Test
    void messagesCarryMediaAndProviderStateThroughJson() {
        Message original = Message.user("what is this?", Media.of(new byte[]{1}, "image/png"))
                .withProviderState(new ProviderState("anthropic", "[{\"type\":\"thinking\"}]"));

        Message copy = Message.fromJson(original.toJson());

        assertEquals(original.getMedia(), copy.getMedia());
        assertEquals(original.getProviderState(), copy.getProviderState());
        assertEquals("what is this?", copy.getContent());
    }

    @Test
    void capabilitiesExplainWhatIsNotSupported() {
        ModelCapabilities imagesOnly = ModelCapabilities.of(Modality.IMAGE);
        List<Message> audio = List.of(Message.user("transcribe", Media.of(new byte[1], "audio/mpeg")));
        List<Message> imageUrl = List.of(Message.user("look", Media.fromUrl("https://x.io/a.png")));

        UnsupportedMediaException noAudio = assertThrows(UnsupportedMediaException.class,
                () -> imagesOnly.check(audio, List.of(), "Acme", "m1"));
        assertEquals("Acme model 'm1' does not accept audio input (audio/mpeg). It accepts: image, text",
                noAudio.getMessage());
        UnsupportedMediaException noUrl = assertThrows(UnsupportedMediaException.class,
                () -> imagesOnly.check(imageUrl, List.of(), "Acme", "m1"));
        assertTrue(noUrl.getMessage().contains("cannot fetch image from a URL"), noUrl.getMessage());
        assertDoesNotThrow(() -> imagesOnly.withMediaUrls(Modality.IMAGE).check(imageUrl, List.of(), "Acme", "m1"));

        List<ToolSchema> tools = List.of(new ToolSchema("t", "d", "{}"));
        assertThrows(UnsupportedMediaException.class,
                () -> ModelCapabilities.textOnly().withToolCalling(false).check(List.of(), tools, "Acme", "m1"));
        assertEquals(Set.of(Modality.TEXT, Modality.IMAGE), imagesOnly.input());
    }

    @Test
    void sha256IdentifiesTheContent() {
        // The well-known SHA-256 of "abc".
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                Media.of("abc".getBytes(), "text/plain").sha256());
        assertEquals(Media.of(new byte[]{1, 2}, "image/png").sha256(), Media.of(new byte[]{1, 2}, "image/jpeg").withName("x").sha256(),
                "same bytes, same hash, whatever the type or name");
        assertNotEquals(Media.of(new byte[]{1, 2}, "image/png").sha256(), Media.of(new byte[]{1, 3}, "image/png").sha256());
        assertNull(Media.fromUrl("https://example.com/cat.jpg").sha256());
    }

    @Test
    void urlMediaHasNoBytes() {
        Media url = Media.fromUrl("https://example.com/files/REPORT.PDF?download=1", "application/pdf").withName("report.pdf");

        assertNull(url.data());
        assertNull(url.base64());
        assertEquals(-1, url.size());
        assertEquals("report.pdf", url.name());
        assertEquals(Modality.DOCUMENT, Media.fromUrl("https://example.com/files/REPORT.PDF").modality(), "type from the URL path");
        assertEquals(url, Media.fromJson(url.toJson()));
        assertTrue(url.toString().contains("https://example.com"), url.toString());
        assertThrows(IllegalArgumentException.class, () -> Media.fromUrl("not a url", "image/png"));
    }

    @Test
    void equalityComparesContentTypeAndName() {
        Media a = Media.of(new byte[]{1, 2}, "image/png").withName("a.png");

        assertEquals(a, Media.fromBase64("AQI=", "IMAGE/PNG").withName("a.png"));
        assertEquals(a.hashCode(), Media.of(new byte[]{1, 2}, "image/png").withName("a.png").hashCode());
        assertNotEquals(a, Media.of(new byte[]{1, 2}, "image/png").withName("b.png"));
        assertNotEquals(a, Media.of(new byte[]{1, 2}, "image/jpeg").withName("a.png"));
        assertNotEquals(a, Media.of(new byte[]{9}, "image/png").withName("a.png"));
        assertEquals("Media{image/png, 2 bytes, a.png}", a.toString());
    }

    @Test
    void fileExtensionsAndUnreadableFiles() throws Exception {
        assertEquals("mp3", Media.of(new byte[1], "audio/mpeg").fileExtension());
        assertEquals("mov", Media.of(new byte[1], "video/quicktime").fileExtension());
        assertEquals("bin", Media.of(new byte[1], "image/x-unusual").fileExtension());

        assertThrows(java.io.UncheckedIOException.class, () -> Media.fromFile(dir.resolve("missing.png")));
        Path unknown = Files.write(dir.resolve("data.unknowntype"), new byte[]{1});
        assertThrows(IllegalArgumentException.class, () -> Media.fromFile(unknown));
        assertThrows(NullPointerException.class, () -> Media.of(null, "image/png"));
    }
}
