package br.com.codelikeaboss.skillsommelier.service;

import br.com.codelikeaboss.skillsommelier.model.InstalledSkill;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LockFileTest {

    @TempDir
    Path tmp;

    @Test
    void missingFileIsEmpty() throws IOException {
        assertTrue(LockFile.load(tmp).skills().isEmpty());
        assertFalse(Files.exists(LockFile.pathFor(tmp)));
    }

    @Test
    void roundTripSortedAndReplacesSameSlot() throws IOException {
        LockFile lock = LockFile.load(tmp);
        lock.put(new InstalledSkill("zeta", "claude", "src", "https://x/s.git", "sha256:1"));
        lock.put(new InstalledSkill("alpha", "claude", "src", "https://x/s.git", "sha256:2"));
        lock.put(new InstalledSkill("alpha", "opencode", "src", "https://x/s.git", "sha256:3"));
        lock.put(new InstalledSkill("zeta", "claude", "src", "https://x/s.git", "sha256:4"));
        lock.save();

        LockFile reloaded = LockFile.load(tmp);
        assertEquals(3, reloaded.skills().size());
        assertEquals("alpha", reloaded.skills().get(0).getName());
        assertEquals("sha256:4", reloaded.find("zeta", "claude").orElseThrow().getHash());
        assertEquals("sha256:3", reloaded.find("alpha", "opencode").orElseThrow().getHash());

        String json = Files.readString(LockFile.pathFor(tmp));
        assertTrue(json.contains("\"version\" : 1"));
        assertFalse(json.toLowerCase().contains("token"));
    }

    @Test
    void removeDropsEntry() throws IOException {
        LockFile lock = LockFile.load(tmp);
        InstalledSkill a = new InstalledSkill("a", "claude", "s", "u", "h");
        lock.put(a);
        assertTrue(lock.remove(new InstalledSkill("a", "claude", null, null, null)));
        assertFalse(lock.remove(a));
        assertTrue(lock.skills().isEmpty());
    }

    @Test
    void saveIfChangedWritesOnlyNewContent() throws IOException {
        LockFile lock = LockFile.load(tmp);
        lock.put(new InstalledSkill("a", "claude", "s", "u", "h"));
        assertTrue(lock.saveIfChanged());
        assertFalse(LockFile.load(tmp).saveIfChanged());

        LockFile reloaded = LockFile.load(tmp);
        reloaded.skills().get(0).setCommit("abc");
        assertTrue(reloaded.saveIfChanged());
        assertEquals("abc", LockFile.load(tmp).skills().get(0).getCommit());
    }

    @Test
    void declaredFlagIsOmittedWhenFalse() throws IOException {
        LockFile lock = LockFile.load(tmp);
        InstalledSkill a = new InstalledSkill("a", "claude", "s", "u", "h");
        a.setDeclared(false);
        InstalledSkill b = new InstalledSkill("b", "claude", "s", "u", "h");
        b.setDeclared(true);
        lock.put(a);
        lock.put(b);
        lock.save();

        String json = Files.readString(LockFile.pathFor(tmp));
        assertEquals(1, json.split("\"declared\"", -1).length - 1);
        assertTrue(LockFile.load(tmp).find("b", "claude").orElseThrow().fromDeclaration());
        assertFalse(LockFile.load(tmp).find("a", "claude").orElseThrow().fromDeclaration());
    }

    @Test
    void ignoresUnknownFields() throws IOException {
        Files.writeString(LockFile.pathFor(tmp),
                "{\"version\":1,\"future\":true,\"skills\":[{\"name\":\"a\",\"target\":\"claude\",\"url\":\"u\",\"extra\":1}]}");
        assertEquals("a", LockFile.load(tmp).skills().get(0).getName());
    }
}
