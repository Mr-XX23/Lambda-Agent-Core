package ai.lambda.examples.skillsAgent;

import ai.lambda.agent.core.Skill;
import ai.lambda.agent.core.Skills;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Keeps the example's skill files valid: a broken SKILL.md fails the build, not the demo. */
class BundledSkillsTest {

    @Test
    void bundledSkillsLoad() {
        Skills skills = Skills.load(Path.of("skills"));

        assertEquals(List.of("commit-message", "release-notes"), skills.all().stream().map(Skill::name).toList());
    }

    @Test
    void releaseNotesTemplateExists() {
        Skill releaseNotes = Skills.load(Path.of("skills")).find("release-notes").orElseThrow();

        assertTrue(releaseNotes.instructions().contains("template.md"));
        assertTrue(Files.isRegularFile(releaseNotes.directory().resolve("template.md")));
    }
}
