package ai.lambda.agent.core;

import ai.lambda.ai.core.Message;
import ai.lambda.ai.core.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SkillsTest {

    @TempDir
    Path root;

    private Path skill(String folder, String skillMd) throws IOException {
        Path dir = Files.createDirectories(root.resolve(folder));
        Files.writeString(dir.resolve(Skill.FILE_NAME), skillMd);
        return dir;
    }

    private static ToolResult call(AgentTool tool, String argsJson) throws Exception {
        return tool.execute(new ToolInvocationContext("call", argsJson, new AgentSession("s")));
    }

    private static AgentTool tool(Skills skills, String name) {
        return skills.tools().stream().filter(t -> t.getName().equals(name)).findFirst().orElseThrow();
    }

    // --- Parsing SKILL.md ---

    @Test
    void parsesNameDescriptionAndBody() {
        Skill skill = Skill.parse(root, """
                ---
                name: release-notes
                description: Writes release notes. Use when asked for a changelog.
                license: MIT
                ---

                # Release notes
                Group changes by type.
                """);

        assertEquals("release-notes", skill.name());
        assertEquals("Writes release notes. Use when asked for a changelog.", skill.description());
        assertEquals("# Release notes\nGroup changes by type.", skill.instructions());
    }

    @Test
    void parsesQuotedAndMultiLineDescriptions() {
        Skill quoted = Skill.parse(root, "---\nname: a\ndescription: \"Uses: colons, and \\\"quotes\\\"\"\n---\nbody");
        Skill folded = Skill.parse(root, "---\nname: b\ndescription: >\n  First line\n  second line.\n---\nbody");
        Skill continued = Skill.parse(root, "---\r\nname: c\r\ndescription: Starts here\r\n  and continues.\r\n---\r\nbody");

        assertEquals("Uses: colons, and \"quotes\"", quoted.description());
        assertEquals("First line second line.", folded.description());
        assertEquals("Starts here and continues.", continued.description());
    }

    @Test
    void rejectsInvalidSkillFiles() {
        assertThrows(IllegalArgumentException.class, () -> Skill.parse(root, "# no frontmatter"));
        assertThrows(IllegalArgumentException.class, () -> Skill.parse(root, "---\nname: a\ndescription: x\n"));
        assertThrows(IllegalArgumentException.class, () -> Skill.parse(root, "---\nname: a\n---\nbody"));
        assertThrows(IllegalArgumentException.class, () -> Skill.parse(root, "---\ndescription: x\n---\nbody"));
        assertThrows(IllegalArgumentException.class, () -> Skill.parse(root, "---\nname: Bad Name\ndescription: x\n---\n"));
        assertThrows(IllegalArgumentException.class, () -> Skill.parse(root, "---\nname: " + "a".repeat(65) + "\ndescription: x\n---\n"));
    }

    // --- Loading folders ---

    @Test
    void loadsEverySubfolderWithSkillMdSortedByName() throws IOException {
        skill("zeta", "---\nname: zeta\ndescription: Z.\n---\nZ body");
        skill("alpha", "---\nname: alpha\ndescription: A.\n---\nA body");
        Files.createDirectories(root.resolve("not-a-skill"));

        Skills skills = Skills.load(root);

        assertEquals(List.of("alpha", "zeta"), skills.all().stream().map(Skill::name).toList());
    }

    @Test
    void aFolderWithSkillMdIsASingleSkill() throws IOException {
        Path dir = skill("solo", "---\nname: solo\ndescription: One.\n---\nbody");

        assertEquals("solo", Skills.load(dir).all().get(0).name());
    }

    @Test
    void errorsNameTheBrokenFileAndDuplicatesAreRejected() throws IOException {
        skill("broken", "---\nname: broken\n---\nno description");
        IllegalArgumentException broken = assertThrows(IllegalArgumentException.class, () -> Skills.load(root));
        assertTrue(broken.getMessage().contains("SKILL.md"), broken.getMessage());

        Files.delete(root.resolve("broken").resolve(Skill.FILE_NAME));
        skill("one", "---\nname: same\ndescription: 1\n---\n");
        skill("two", "---\nname: same\ndescription: 2\n---\n");
        IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class, () -> Skills.load(root));
        assertTrue(duplicate.getMessage().contains("Duplicate skill name 'same'"), duplicate.getMessage());
    }

    @Test
    void missingSkillsDirectoryIsAClearError() {
        assertThrows(IllegalArgumentException.class, () -> Skills.load(root.resolve("nope")));
    }

    // --- Tools ---

    @Test
    void loadSkillReturnsInstructionsAndListsOtherFiles() throws Exception {
        Path dir = skill("report", "---\nname: report\ndescription: Reports.\n---\nUse templates/report.md.");
        Files.createDirectories(dir.resolve("templates"));
        Files.writeString(dir.resolve("templates/report.md"), "# Title");
        Skills skills = Skills.load(root);

        String out = call(tool(skills, "load_skill"), "{\"name\":\"report\"}").getContent();

        assertTrue(out.contains("Use templates/report.md."), out);
        assertTrue(out.contains("- templates/report.md"), out);
        assertFalse(out.contains("- SKILL.md"), out);
    }

    @Test
    void unknownSkillNameListsTheAvailableOnes() throws Exception {
        skill("report", "---\nname: report\ndescription: Reports.\n---\nbody");
        Skills skills = Skills.load(root);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> call(tool(skills, "load_skill"), "{\"name\":\"reprot\"}"));

        assertTrue(e.getMessage().contains("Available skills: report"), e.getMessage());
    }

    @Test
    void readSkillFileReadsInsideTheSkillOnly() throws Exception {
        Path dir = skill("report", "---\nname: report\ndescription: Reports.\n---\nbody");
        Files.writeString(dir.resolve("template.md"), "TEMPLATE");
        Files.writeString(root.resolve("secret.txt"), "SECRET");
        AgentTool read = tool(Skills.load(root), "read_skill_file");

        assertEquals("TEMPLATE", call(read, "{\"skill\":\"report\",\"path\":\"template.md\"}").getContent());
        assertThrows(SecurityException.class,
                () -> call(read, "{\"skill\":\"report\",\"path\":\"../secret.txt\"}"));
        String absolute = root.resolve("secret.txt").toAbsolutePath().toString().replace("\\", "\\\\");
        assertThrows(SecurityException.class,
                () -> call(read, "{\"skill\":\"report\",\"path\":\"" + absolute + "\"}"));
        assertThrows(IllegalArgumentException.class,
                () -> call(read, "{\"skill\":\"report\",\"path\":\"missing.md\"}"));
    }

    @Test
    void readSkillFileDoesNotFollowSymlinksOutOfTheSkill() throws Exception {
        Path dir = skill("report", "---\nname: report\ndescription: Reports.\n---\nbody");
        Path secret = Files.writeString(root.resolve("secret.txt"), "SECRET");
        try {
            Files.createSymbolicLink(dir.resolve("link.txt"), secret);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            assumeTrue(false, "symbolic links are not available here: " + e);
        }
        AgentTool read = tool(Skills.load(root), "read_skill_file");

        assertThrows(SecurityException.class, () -> call(read, "{\"skill\":\"report\",\"path\":\"link.txt\"}"));
    }

    // --- Wiring into the agent ---

    @Test
    void withSkillsAddsPromptSectionAndToolsButKeepsTheRest() throws IOException {
        skill("report", "---\nname: report\ndescription: Writes weekly reports.\n---\nbody");
        var base = new AgentConfig("Be brief.", new FakeModelClient(), List.of(), 3)
                .withContextStrategy(new SlidingWindowStrategy(10));

        AgentConfig config = base.withSkills(Skills.load(root));

        assertTrue(config.getSystemPrompt().startsWith("Be brief.\n\n## Skills"), config.getSystemPrompt());
        assertTrue(config.getSystemPrompt().contains("- report: Writes weekly reports."), config.getSystemPrompt());
        assertEquals(List.of("load_skill", "read_skill_file"),
                config.getTools().stream().map(AgentTool::getName).toList());
        assertInstanceOf(SlidingWindowStrategy.class, config.getContextStrategy());
        assertEquals(3, config.getMaxIterations());
        assertSame(base, base.withSkills(Skills.of(List.of())), "no skills, no change");
    }

    @Test
    void agentLoadsASkillAndSeesItsInstructions() throws IOException {
        skill("haiku", "---\nname: haiku\ndescription: Writes haiku.\n---\nUse 5-7-5 syllables.");
        var model = new FakeModelClient()
                .replyToolCall("c1", "load_skill", "{\"name\":\"haiku\"}")
                .replyText("An old silent pond...");
        var config = new AgentConfig("You are a poet.", model, List.of(), 5).withSkills(Skills.load(root));

        AgentResult result = new Agent(config, new InMemorySessionStore()).run("s", "write a haiku");

        assertEquals("An old silent pond...", result.getFinalText());
        Message system = model.requests.get(0).get(0);
        assertEquals(Role.SYSTEM, system.getRole());
        assertTrue(system.getContent().contains("- haiku: Writes haiku."), system.getContent());
        Message toolResult = model.requests.get(1).get(3);
        assertEquals("load_skill", toolResult.getToolCallName());
        assertTrue(toolResult.getContent().contains("Use 5-7-5 syllables."), toolResult.getContent());
    }
}
