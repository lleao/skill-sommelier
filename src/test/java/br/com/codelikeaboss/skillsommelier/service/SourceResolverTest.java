package br.com.codelikeaboss.skillsommelier.service;

import br.com.codelikeaboss.skillsommelier.model.SkillCatalog;
import br.com.codelikeaboss.skillsommelier.model.SkillSource;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SourceResolverTest {

    @TempDir
    Path tmp;

    private final GitClient git = new GitClient();

    private SourceResolver resolver(SkillCatalog catalog) {
        return new SourceResolver(catalog, tmp.resolve("cache"), tmp.resolve("project"), git, new SystemStreamLog());
    }

    @Test
    void findUsesCatalogNameAndFallsBackToRawValue() {
        SkillCatalog catalog = new SkillCatalog();
        catalog.setSources(List.of(new SkillSource("mine", "https://example.com/org/skills.git", "t")));
        SourceResolver r = resolver(catalog);

        assertEquals("https://example.com/org/skills.git", r.find("mine").getUrl());
        assertEquals("t", r.find("mine").getToken());

        SkillSource raw = r.find("https://example.com/other.git");
        assertEquals("https://example.com/other.git", raw.getUrl());
        assertNull(raw.getToken());
    }

    @Test
    void sourcesIsEmptyWithoutCatalog() {
        assertTrue(resolver(null).sources().isEmpty());
    }

    @Test
    void localDirectoryIsUsedInPlace() throws IOException {
        Path local = Files.createDirectories(tmp.resolve("local-src"));
        Path resolved = resolver(null).resolve(new SkillSource("local", local.toString(), null));
        assertEquals(local.toAbsolutePath().normalize(), resolved);
        assertFalse(Files.exists(tmp.resolve("cache")));
    }

    @Test
    void cachePathKeepsSameNamedRepositoriesApart() {
        SourceResolver r = resolver(null);
        Path a = r.cachePathFor("https://github.com/alice/skills.git");
        Path b = r.cachePathFor("https://github.com/bob/skills.git");
        assertNotEquals(a, b);
        assertTrue(a.getFileName().toString().startsWith("skills-"));
        assertTrue(r.cachePathFor("git@github.com:alice/skills.git").getFileName().toString().startsWith("skills-"));
        assertEquals(a, r.cachePathFor("https://github.com/alice/skills.git/"));
    }

    @Test
    void remoteSourceIsClonedCachedAndUpdated() throws IOException {
        Path origin = createOriginRepo();
        String url = origin.toUri().toString(); // file:// URL, not an existing directory path
        SkillSource source = new SkillSource("remote", url, null);
        SourceResolver r = resolver(null);

        Path cloned = r.resolve(source);
        assertTrue(cloned.startsWith(tmp.resolve("cache")));
        assertTrue(Files.isRegularFile(cloned.resolve("skills/demo/SKILL.md")));
        assertEquals(cloned, r.resolve(source), "second resolve must reuse the cache");

        Files.createDirectories(origin.resolve("skills/second"));
        Files.writeString(origin.resolve("skills/second/SKILL.md"), "---\nname: second\n---\n");
        git.run(origin, null, "add", ".");
        git.run(origin, null, "commit", "-q", "-m", "second");

        assertFalse(Files.exists(cloned.resolve("skills/second")));
        r.update(source);
        assertTrue(Files.isRegularFile(cloned.resolve("skills/second/SKILL.md")));
    }

    @Test
    void refreshPullsOnlyWhenStaleAndNeverOffline() throws IOException {
        Path origin = createOriginRepo();
        SkillSource source = new SkillSource("remote", origin.toUri().toString(), null);
        SourceResolver r = resolver(null);

        assertThrows(IOException.class, () -> r.refresh(source, Duration.ZERO, true), "offline without cache");
        Path cloned = r.refresh(source, Duration.ofHours(1), false);

        Files.createDirectories(origin.resolve("skills/second"));
        Files.writeString(origin.resolve("skills/second/SKILL.md"), "x");
        git.run(origin, null, "add", ".");
        git.run(origin, null, "commit", "-q", "-m", "second");

        r.refresh(source, Duration.ofHours(1), false);
        assertFalse(Files.exists(cloned.resolve("skills/second")), "fresh cache must not be pulled");
        r.refresh(source, Duration.ZERO, true);
        assertFalse(Files.exists(cloned.resolve("skills/second")), "offline must not pull");
        r.refresh(source, Duration.ZERO, false);
        assertTrue(Files.exists(cloned.resolve("skills/second")));
    }

    @Test
    void findPrefersCatalogNameThenUrl() {
        SkillCatalog catalog = new SkillCatalog();
        catalog.setSources(List.of(new SkillSource("mine", "https://new/skills.git", "t")));
        SourceResolver r = resolver(catalog);

        assertEquals("https://new/skills.git", r.find("mine", "https://old/skills.git").getUrl());
        assertEquals("t", r.find("renamed", "https://new/skills.git").getToken());
        SkillSource unknown = r.find("other", "https://other.git");
        assertEquals("https://other.git", unknown.getUrl());
        assertNull(unknown.getToken());
    }

    @Test
    void relativeLocalPathIsResolvedAgainstProjectDir() throws IOException {
        Path source = Files.createDirectories(tmp.resolve("shared-skills"));
        Files.createDirectories(tmp.resolve("project"));
        SourceResolver r = resolver(null);
        SkillSource relative = new SkillSource("rel", "../shared-skills", null);

        assertEquals(source.toAbsolutePath().normalize(), r.resolve(relative));
        assertEquals("../shared-skills", r.recordedUrl(new SkillSource("abs", source.toString(), null)),
                "local sources are recorded relative to the project");
        assertEquals("https://x/y.git", r.recordedUrl(new SkillSource("remote", "https://x/y.git", null)));
    }

    @Test
    void failedAttemptIsNotRetriedWithinInterval() throws IOException {
        Path origin = tmp.resolve("origin-later");
        SkillSource source = new SkillSource("later", origin.toUri().toString(), null);
        SourceResolver r = resolver(null);

        IOException first = assertThrows(IOException.class, () -> r.refresh(source, Duration.ofHours(1), false));
        assertInstanceOf(GitClient.GitException.class, first);

        createOriginRepo(origin);
        IOException second = assertThrows(IOException.class, () -> r.refresh(source, Duration.ofHours(1), false));
        assertTrue(second.getMessage().contains("retrying after the update interval"), second.getMessage());

        assertTrue(Files.isDirectory(r.refresh(source, Duration.ZERO, false).resolve("skills/demo")),
                "once the interval passed, the source is fetched again");
    }

    @Test
    void refreshSurvivesRewrittenHistory() throws IOException {
        Path origin = createOriginRepo();
        SkillSource source = new SkillSource("remote", origin.toUri().toString(), null);
        SourceResolver r = resolver(null);
        Path cloned = r.resolve(source);

        Files.writeString(origin.resolve("skills/demo/SKILL.md"), "rewritten");
        git.run(origin, null, "add", ".");
        git.run(origin, null, "commit", "-q", "--amend", "-m", "rewritten");

        r.update(source);
        assertEquals("rewritten", Files.readString(cloned.resolve("skills/demo/SKILL.md")));
    }

    @Test
    void cloneIsSparseAndOnlyChecksOutSkills() throws IOException {
        Path origin = createOriginRepo();
        Files.createDirectories(origin.resolve("docs"));
        Files.writeString(origin.resolve("docs/big.txt"), "not needed");
        git.run(origin, null, "add", ".");
        git.run(origin, null, "commit", "-q", "-m", "docs");

        Path cloned = resolver(null).resolve(new SkillSource("remote", origin.toUri().toString(), null));
        assertTrue(Files.exists(cloned.resolve("skills/demo/SKILL.md")));
        assertFalse(Files.exists(cloned.resolve("docs")));
    }

    @Test
    void cleanDeletesStaleAndLegacyClones() throws IOException {
        Path origin = createOriginRepo();
        SourceResolver r = resolver(null);
        Path fresh = r.resolve(new SkillSource("remote", origin.toUri().toString(), null));
        Path legacy = Files.createDirectories(tmp.resolve("cache/legacy-clone/.git"));

        assertEquals(List.of(legacy.getParent()), r.clean(Duration.ofDays(30), false));
        assertTrue(Files.exists(fresh));
        assertEquals(List.of(fresh), r.clean(Duration.ofDays(30), true));
        assertFalse(Files.exists(fresh));
    }

    @Test
    void cloneFailureReportsGitOutput() {
        SkillSource source = new SkillSource("missing", tmp.resolve("does-not-exist").toUri().toString(), null);
        GitClient.GitException e = assertThrows(GitClient.GitException.class, () -> resolver(null).resolve(source));
        assertFalse(e.getOutput().isEmpty());
    }

    @Test
    void pinnedRefIsClonedInItsOwnCacheAndRecorded() throws IOException {
        Path origin = createOriginRepo();
        String url = origin.toUri().toString();
        git.run(origin, null, "tag", "-a", "v1", "-m", "v1");
        String v1 = git.headCommit(origin);
        Files.writeString(origin.resolve("skills/demo/SKILL.md"), "v2");
        git.run(origin, null, "commit", "-q", "-am", "v2");
        SourceResolver r = resolver(null);

        SkillSource latest = new SkillSource("s", url, null);
        SkillSource byTag = new SkillSource("s", url, null, "v1");
        SkillSource byCommit = new SkillSource("s", url, null, v1);
        Path latestDir = r.resolve(latest);
        Path tagDir = r.resolve(byTag);
        Path commitDir = r.resolve(byCommit);

        assertEquals("v2", Files.readString(latestDir.resolve("skills/demo/SKILL.md")));
        assertEquals("---\nname: demo\n---\n", Files.readString(tagDir.resolve("skills/demo/SKILL.md")));
        assertEquals(Files.readString(tagDir.resolve("skills/demo/SKILL.md")),
                Files.readString(commitDir.resolve("skills/demo/SKILL.md")));
        assertEquals(3, List.of(latestDir, tagDir, commitDir).stream().distinct().count(), "one clone per ref");

        assertEquals(v1, r.commit(byTag).orElseThrow());
        assertEquals(git.headCommit(origin), r.commit(latest).orElseThrow());
        assertEquals("v1", r.recordedRef(byTag));

        r.update(byTag);
        assertEquals(v1, r.commit(byTag).orElseThrow(), "update keeps a pinned source on its ref");
    }

    @Test
    void refIsIgnoredForLocalDirectoriesAndValidated() throws IOException {
        Path local = Files.createDirectories(tmp.resolve("local-src"));
        SourceResolver r = resolver(null);
        SkillSource pinned = new SkillSource("local", local.toString(), null, "v1");
        assertNull(r.recordedRef(pinned));
        assertTrue(r.commit(pinned).isEmpty());
        assertEquals(local.toAbsolutePath().normalize(), r.resolve(pinned));

        assertTrue(GitClient.isValidRef("v1.2.0"));
        assertTrue(GitClient.isValidRef("feature/x"));
        assertTrue(GitClient.isValidRef("0123456789abcdef0123456789abcdef01234567"));
        assertFalse(GitClient.isValidRef("--upload-pack=evil"));
        assertFalse(GitClient.isValidRef("a..b"));
        assertFalse(GitClient.isValidRef("with space"));
        assertThrows(IOException.class, () -> r.resolve(new SkillSource("s", "https://x/y.git", null, "-x")));
    }

    @Test
    void findUsesRecordedRefOnlyOutsideCatalog() {
        SkillCatalog catalog = new SkillCatalog();
        catalog.setSources(List.of(new SkillSource("mine", "https://x/s.git", null, "v2")));
        SourceResolver r = resolver(catalog);
        assertEquals("v2", r.find("mine", "https://x/s.git", "v1").getRef());
        assertEquals("v1", r.find("other", "https://y/s.git", "v1").getRef());
    }

    private Path createOriginRepo() throws IOException {
        return createOriginRepo(tmp.resolve("origin"));
    }

    private Path createOriginRepo(Path origin) throws IOException {
        Files.createDirectories(origin);
        Files.createDirectories(origin.resolve("skills/demo"));
        Files.writeString(origin.resolve("skills/demo/SKILL.md"), "---\nname: demo\n---\n");
        git.run(origin, null, "init", "-q");
        git.run(origin, null, "config", "user.email", "test@example.com");
        git.run(origin, null, "config", "user.name", "Test");
        git.run(origin, null, "config", "commit.gpgsign", "false");
        git.run(origin, null, "add", ".");
        git.run(origin, null, "commit", "-q", "-m", "init");
        return origin;
    }
}
