package br.com.codelikeaboss.skillsommelier.service;

import br.com.codelikeaboss.skillsommelier.model.DeclaredSkill;
import br.com.codelikeaboss.skillsommelier.model.InstalledSkill;
import br.com.codelikeaboss.skillsommelier.model.SkillCatalog;
import br.com.codelikeaboss.skillsommelier.model.SkillSource;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static br.com.codelikeaboss.skillsommelier.service.SkillSync.Outcome.*;
import static org.junit.jupiter.api.Assertions.*;

class SkillSyncTest {

    @TempDir
    Path tmp;

    private Path sourceRoot;
    private Path project;
    private final SkillInstaller installer = new SkillInstaller();
    private SourceResolver resolver;

    @BeforeEach
    void setUp() throws IOException {
        sourceRoot = tmp.resolve("source");
        write(sourceRoot.resolve("skills/demo/SKILL.md"), "v1");
        write(sourceRoot.resolve("skills/demo/extra.txt"), "extra");
        project = Files.createDirectories(tmp.resolve("project"));

        SkillCatalog catalog = new SkillCatalog();
        catalog.setSources(List.of(new SkillSource("local", sourceRoot.toString(), null)));
        resolver = new SourceResolver(catalog, tmp.resolve("cache"), project, new GitClient(), new SystemStreamLog());
    }

    private LockFile installDemo() throws IOException {
        Path installed = installer.install(sourceRoot.resolve("skills"), "demo", project.resolve(".claude/skills")).destination();
        LockFile lock = LockFile.load(project);
        lock.put(new InstalledSkill("demo", "claude", "local", sourceRoot.toString(), installer.hash(installed)));
        lock.save();
        return LockFile.load(project);
    }

    private SkillSync.Result syncOnce(LockFile lock, boolean force) {
        List<SkillSync.Result> results = new SkillSync(resolver, installer, new SystemStreamLog())
                .sync(project, lock, Duration.ZERO, false, force);
        assertEquals(1, results.size());
        return results.get(0);
    }

    private Path installedFile(String name) {
        return project.resolve(".claude/skills/demo").resolve(name);
    }

    @Test
    void sourceAndInstalledCopyHaveSameHash() throws IOException {
        installDemo();
        assertEquals(installer.hash(sourceRoot.resolve("skills/demo")), installer.hash(project.resolve(".claude/skills/demo")));
    }

    @Test
    void unchangedSourceIsUpToDate() throws IOException {
        assertEquals(UP_TO_DATE, syncOnce(installDemo(), false).outcome());
    }

    @Test
    void upstreamChangeIsCopiedAndLockUpdated() throws IOException {
        LockFile lock = installDemo();
        String before = lock.skills().get(0).getHash();
        write(sourceRoot.resolve("skills/demo/SKILL.md"), "v2");
        Files.delete(sourceRoot.resolve("skills/demo/extra.txt"));

        assertEquals(UPDATED, syncOnce(lock, false).outcome());
        assertEquals("v2", Files.readString(installedFile("SKILL.md")));
        assertFalse(Files.exists(installedFile("extra.txt")), "files removed upstream must be removed");
        assertNotEquals(before, lock.skills().get(0).getHash());
        assertEquals(UP_TO_DATE, syncOnce(lock, false).outcome());
    }

    @Test
    void localEditsAreKeptUnlessForced() throws IOException {
        LockFile lock = installDemo();
        write(installedFile("SKILL.md"), "my edit");
        write(sourceRoot.resolve("skills/demo/SKILL.md"), "v2");

        assertEquals(MODIFIED_LOCALLY, syncOnce(lock, false).outcome());
        assertEquals("my edit", Files.readString(installedFile("SKILL.md")));

        assertEquals(UPDATED, syncOnce(lock, true).outcome());
        assertEquals("v2", Files.readString(installedFile("SKILL.md")));
    }

    @Test
    void deletedSkillIsRestored() throws IOException {
        LockFile lock = installDemo();
        Files.delete(installedFile("SKILL.md"));
        Files.delete(installedFile("extra.txt"));
        Files.delete(project.resolve(".claude/skills/demo"));

        assertEquals(RESTORED, syncOnce(lock, false).outcome());
        assertEquals("v1", Files.readString(installedFile("SKILL.md")));
    }

    @Test
    void skillRemovedUpstreamIsReportedAndKept() throws IOException {
        LockFile lock = installDemo();
        Files.delete(sourceRoot.resolve("skills/demo/SKILL.md"));
        Files.delete(sourceRoot.resolve("skills/demo/extra.txt"));
        Files.delete(sourceRoot.resolve("skills/demo"));

        assertEquals(MISSING_UPSTREAM, syncOnce(lock, false).outcome());
        assertTrue(Files.exists(installedFile("SKILL.md")));
    }

    @Test
    void unreachableSourceIsReported() throws IOException {
        LockFile lock = LockFile.load(project);
        lock.put(new InstalledSkill("demo", "claude", "gone", tmp.resolve("nope").toUri().toString(), "sha256:x"));
        SkillSync.Result result = syncOnce(lock, false);
        assertEquals(SOURCE_UNAVAILABLE, result.outcome());
        assertTrue(result.detail().contains("gone"));
    }

    @Test
    void invalidEntryIsReported() throws IOException {
        LockFile lock = LockFile.load(project);
        lock.put(new InstalledSkill("../evil", "claude", "local", sourceRoot.toString(), "sha256:x"));
        assertEquals(INVALID_ENTRY, syncOnce(lock, false).outcome());
    }

    private SkillSync sync() {
        return new SkillSync(resolver, installer, new SystemStreamLog());
    }

    private List<SkillSync.Outcome> outcomes(List<SkillSync.Result> results) {
        return results.stream().map(SkillSync.Result::outcome).toList();
    }

    private List<SkillSync.Outcome> syncDeclared(LockFile lock, DeclaredSkill... declared) {
        return outcomes(sync().sync(project, lock, List.of(declared), Duration.ZERO, false, false));
    }

    private List<SkillSync.Outcome> check(LockFile lock, boolean upstream, DeclaredSkill... declared) {
        return outcomes(sync().check(project, lock, List.of(declared), upstream, Duration.ZERO, false));
    }

    @Test
    void declaredSkillIsInstalledIntoEveryTarget() throws IOException {
        LockFile lock = LockFile.load(project);
        assertEquals(List.of(INSTALLED, INSTALLED), syncDeclared(lock, new DeclaredSkill("demo", null, "claude, copilot")));

        assertTrue(Files.isRegularFile(project.resolve(".claude/skills/demo/SKILL.md")));
        assertTrue(Files.isRegularFile(project.resolve(".github/skills/demo/SKILL.md")));
        InstalledSkill entry = lock.find("demo", "copilot").orElseThrow();
        assertTrue(entry.fromDeclaration());
        assertEquals("local", entry.getSource(), "the only catalog source is the default");
        assertEquals(installer.hash(sourceRoot.resolve("skills/demo")), entry.getHash());

        assertEquals(List.of(UP_TO_DATE, UP_TO_DATE), syncDeclared(lock, new DeclaredSkill("demo", "local", "claude,copilot")));
    }

    @Test
    void removedDeclarationUninstallsUnlessEditedLocally() throws IOException {
        LockFile lock = LockFile.load(project);
        syncDeclared(lock, new DeclaredSkill("demo", "local", "claude,copilot"));
        write(project.resolve(".github/skills/demo/SKILL.md"), "my edit");

        List<SkillSync.Outcome> result = syncDeclared(lock, new DeclaredSkill("other-skill-not-needed", "local", "opencode"));
        assertTrue(result.containsAll(List.of(UNINSTALLED, MODIFIED_LOCALLY)), result.toString());
        assertFalse(Files.exists(project.resolve(".claude/skills/demo")));
        assertEquals("my edit", Files.readString(project.resolve(".github/skills/demo/SKILL.md")));
        assertTrue(lock.find("demo", "claude").isEmpty());
        assertTrue(lock.find("demo", "copilot").isEmpty());
        assertTrue(lock.find("other-skill-not-needed", "opencode").isEmpty(),
                "declared skills that could not be installed are not recorded");
    }

    @Test
    void skillsAddedByHandAreNeverUninstalledByDeclarations() throws IOException {
        LockFile lock = installDemo();
        assertEquals(List.of(UP_TO_DATE), syncDeclared(lock));
        assertTrue(Files.exists(installedFile("SKILL.md")));
    }

    @Test
    void invalidDeclarationPreventsUninstalling() throws IOException {
        LockFile lock = LockFile.load(project);
        syncDeclared(lock, new DeclaredSkill("demo", "local", "claude"));

        List<SkillSync.Outcome> result = syncDeclared(lock, new DeclaredSkill("demo", "local", "claud"));
        assertEquals(List.of(INVALID_ENTRY, UP_TO_DATE), result);
        assertTrue(Files.exists(installedFile("SKILL.md")));
    }

    @Test
    void existingFolderIsAdoptedOnlyWhenIdentical() throws IOException {
        installer.install(sourceRoot.resolve("skills"), "demo", project.resolve(".claude/skills"));
        LockFile lock = LockFile.load(project);
        assertEquals(List.of(INSTALLED), syncDeclared(lock, new DeclaredSkill("demo", "local", "claude")));

        write(project.resolve(".opencode/skills/demo/SKILL.md"), "someone else's skill");
        assertEquals(List.of(UP_TO_DATE, MODIFIED_LOCALLY),
                syncDeclared(lock, new DeclaredSkill("demo", "local", "claude,opencode")));
        assertEquals("someone else's skill", Files.readString(project.resolve(".opencode/skills/demo/SKILL.md")));
        assertTrue(lock.find("demo", "opencode").isEmpty());
    }

    @Test
    void checkReportsWithoutChangingAnything() throws IOException {
        LockFile lock = installDemo();
        assertEquals(List.of(UP_TO_DATE), check(lock, true));

        write(sourceRoot.resolve("skills/demo/SKILL.md"), "v2");
        assertEquals(List.of(OUTDATED), check(lock, true));
        assertEquals(List.of(UP_TO_DATE), check(lock, false), "local check ignores the source");
        assertEquals("v1", Files.readString(installedFile("SKILL.md")));

        write(installedFile("SKILL.md"), "my edit");
        assertEquals(List.of(MODIFIED_LOCALLY), check(lock, false));

        LockFile fresh = LockFile.load(project);
        assertEquals(List.of(MODIFIED_LOCALLY, NOT_INSTALLED),
                check(fresh, true, new DeclaredSkill("demo", "local", "opencode")));
        assertFalse(Files.exists(project.resolve(".opencode")));
        assertEquals(1, LockFile.load(project).skills().size(), "check never writes the lock file");
    }

    @Test
    void checkReportsUndeclaredAndMissingSkills() throws IOException {
        LockFile lock = LockFile.load(project);
        syncDeclared(lock, new DeclaredSkill("demo", "local", "claude"));
        lock.save();

        assertEquals(List.of(UNDECLARED), check(LockFile.load(project), true));
        assertTrue(Files.exists(installedFile("SKILL.md")));

        FileTrees.delete(project.resolve(".claude/skills/demo"));
        assertEquals(List.of(NOT_INSTALLED), check(LockFile.load(project), false, new DeclaredSkill("demo", "local", "claude")));
    }

    @Test
    void remoteSourceRecordsRefAndCommit() throws IOException {
        GitClient git = new GitClient();
        Path origin = sourceRoot;
        git.run(origin, null, "init", "-q");
        git.run(origin, null, "config", "user.email", "test@example.com");
        git.run(origin, null, "config", "user.name", "Test");
        git.run(origin, null, "config", "commit.gpgsign", "false");
        git.run(origin, null, "add", ".");
        git.run(origin, null, "commit", "-q", "-m", "v1");
        git.run(origin, null, "tag", "v1");
        String v1 = git.headCommit(origin);

        SkillCatalog catalog = new SkillCatalog();
        catalog.setSources(List.of(new SkillSource("remote", origin.toUri().toString(), null, "v1")));
        resolver = new SourceResolver(catalog, tmp.resolve("cache"), project, git, new SystemStreamLog());
        LockFile lock = LockFile.load(project);
        assertEquals(List.of(INSTALLED), syncDeclared(lock, new DeclaredSkill("demo", "remote", "claude")));
        InstalledSkill entry = lock.find("demo", "claude").orElseThrow();
        assertEquals("v1", entry.getRef());
        assertEquals(v1, entry.getCommit());

        write(origin.resolve("skills/demo/SKILL.md"), "v2");
        git.run(origin, null, "commit", "-q", "-am", "v2");
        assertEquals(List.of(UP_TO_DATE), syncDeclared(lock, new DeclaredSkill("demo", "remote", "claude")),
                "a pinned source ignores newer commits");

        catalog.getSources().get(0).setRef(null);
        assertEquals(List.of(OUTDATED), check(lock, true, new DeclaredSkill("demo", "remote", "claude")));
        assertEquals(List.of(UPDATED), syncDeclared(lock, new DeclaredSkill("demo", "remote", "claude")));
        assertNull(entry.getRef());
        assertEquals(git.headCommit(origin), entry.getCommit());
        assertEquals("v2", Files.readString(installedFile("SKILL.md")));
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
