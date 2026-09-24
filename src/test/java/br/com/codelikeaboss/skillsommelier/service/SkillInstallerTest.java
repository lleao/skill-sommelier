package br.com.codelikeaboss.skillsommelier.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SkillInstallerTest {

    @TempDir
    Path tmp;

    private Path skillsDir;
    private final SkillInstaller installer = new SkillInstaller();

    @BeforeEach
    void setUp() throws IOException {
        skillsDir = Files.createDirectories(tmp.resolve("source/skills"));
        Files.createDirectories(skillsDir.resolve("kick-off/scripts"));
        Files.writeString(skillsDir.resolve("kick-off/SKILL.md"), "---\nname: kick-off\n---\n");
        Files.writeString(skillsDir.resolve("kick-off/scripts/run.sh"), "echo hi\n");
        Files.createDirectories(skillsDir.resolve("another"));
        Files.writeString(skillsDir.resolve("README.md"), "not a skill");
    }

    @Test
    void listsOnlyDirectoriesSorted() throws IOException {
        assertEquals(List.of("another", "kick-off"), installer.list(skillsDir));
    }

    @Test
    void listFailsWithoutSkillsDir() {
        assertThrows(NoSuchFileException.class, () -> installer.list(tmp.resolve("nope")));
    }

    @Test
    void installsRecursivelyAndOverwrites() throws IOException {
        Path target = tmp.resolve("project/.claude/skills");
        Files.createDirectories(target.resolve("kick-off"));
        Files.writeString(target.resolve("kick-off/SKILL.md"), "old");

        Path installed = installer.install(skillsDir, "kick-off", target).destination();

        assertEquals(target.resolve("kick-off"), installed);
        assertEquals("---\nname: kick-off\n---\n", Files.readString(installed.resolve("SKILL.md")));
        assertTrue(Files.isRegularFile(installed.resolve("scripts/run.sh")));
    }

    @Test
    void readsDescriptionFromFrontMatter() throws IOException {
        Path plain = Files.createDirectories(tmp.resolve("plain"));
        Files.writeString(plain.resolve("SKILL.md"), "---\nname: plain\ndescription: \"Does things.\"\n---\n# Body\n");
        assertEquals("Does things.", installer.description(plain));

        Path block = Files.createDirectories(tmp.resolve("block"));
        Files.writeString(block.resolve("SKILL.md"),
                "---\nname: block\ndescription: |\n  Generates a project\n  from scratch.\nlicense: MIT\n---\n");
        assertEquals("Generates a project from scratch.", installer.description(block));

        assertEquals("", installer.description(skillsDir.resolve("another")));
        Files.writeString(tmp.resolve("plain/SKILL.md"), "# No front matter\ndescription: nope\n");
        assertEquals("", installer.description(plain));
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "creating symbolic links needs extra privileges")
    void symbolicLinksAreNeitherFollowedNorCopied() throws IOException {
        Path secret = Files.writeString(tmp.resolve("secret.txt"), "SECRET");
        Path outsideDir = Files.createDirectories(tmp.resolve("outside"));
        Files.writeString(outsideDir.resolve("file.txt"), "outside");
        Path evil = Files.createDirectories(skillsDir.resolve("evil"));
        Files.writeString(evil.resolve("README.md"), "ok");
        Files.createSymbolicLink(evil.resolve("SKILL.md"), secret);
        Files.createSymbolicLink(evil.resolve("dir"), outsideDir);
        Files.createSymbolicLink(skillsDir.resolve("linked-skill"), outsideDir);

        SkillInstaller.Installed result = installer.install(skillsDir, "evil", tmp.resolve("t"));

        assertEquals(List.of("SKILL.md", "dir"), result.skippedLinks().stream().sorted().toList());
        assertFalse(Files.exists(result.destination().resolve("SKILL.md"), java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(result.destination().resolve("dir"), java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.exists(result.destination().resolve("README.md")));
        assertEquals(installer.hash(result.destination()), installer.hash(evil), "links are ignored by the hash too");

        assertFalse(installer.list(skillsDir).contains("linked-skill"), "a linked skill folder is not listed");
        assertThrows(NoSuchFileException.class, () -> installer.install(skillsDir, "linked-skill", tmp.resolve("t")));
    }

    @Test
    void hashIgnoresLineEndingsOfTextButNotBinary() throws IOException {
        Path lf = Files.createDirectories(tmp.resolve("lf"));
        Path crlf = Files.createDirectories(tmp.resolve("crlf"));
        Files.writeString(lf.resolve("SKILL.md"), "a\nb\n");
        Files.writeString(crlf.resolve("SKILL.md"), "a\r\nb\r\n");
        assertEquals(installer.hash(lf), installer.hash(crlf));

        Files.write(lf.resolve("bin"), new byte[]{0, '\r', '\n'});
        Files.write(crlf.resolve("bin"), new byte[]{0, '\n'});
        assertNotEquals(installer.hash(lf), installer.hash(crlf));
    }

    @Test
    void removeDeletesInstalledSkill() throws IOException {
        Path target = tmp.resolve("project/.claude/skills");
        installer.install(skillsDir, "kick-off", target);
        assertTrue(installer.remove(target, "kick-off"));
        assertFalse(Files.exists(target.resolve("kick-off")));
        assertFalse(installer.remove(target, "kick-off"));
        assertThrows(IllegalArgumentException.class, () -> installer.remove(target, ".."));
    }

    @Test
    void missingSkillFails() {
        assertThrows(NoSuchFileException.class, () -> installer.install(skillsDir, "ghost", tmp.resolve("t")));
    }

    @Test
    void rejectsPathTraversal() {
        assertFalse(SkillInstaller.isValidName(".."));
        assertFalse(SkillInstaller.isValidName("../etc"));
        assertFalse(SkillInstaller.isValidName("a/b"));
        assertFalse(SkillInstaller.isValidName(".hidden"));
        assertFalse(SkillInstaller.isValidName(""));
        assertTrue(SkillInstaller.isValidName("kick-off"));
        assertTrue(SkillInstaller.isValidName("spring_boot.v2"));
        assertThrows(IllegalArgumentException.class, () -> installer.install(skillsDir, "../source", tmp.resolve("t")));
    }
}
