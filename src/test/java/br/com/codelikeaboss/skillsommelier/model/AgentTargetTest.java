package br.com.codelikeaboss.skillsommelier.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class AgentTargetTest {

    @TempDir
    Path tmp;

    @Test
    void parsesIdOrDirectory() {
        assertEquals(Optional.of(AgentTarget.CLAUDE), AgentTarget.parse("claude"));
        assertEquals(Optional.of(AgentTarget.CLAUDE), AgentTarget.parse(".Claude"));
        assertEquals(Optional.of(AgentTarget.COPILOT), AgentTarget.parse(".github"));
        assertEquals(Optional.of(AgentTarget.OPENCODE), AgentTarget.parse(" opencode "));
        assertTrue(AgentTarget.parse(".idea").isEmpty());
        assertTrue(AgentTarget.parse(null).isEmpty());
    }

    @Test
    void detectsOnlyKnownAgentDirectories() throws IOException {
        Files.createDirectories(tmp.resolve(".git"));
        Files.createDirectories(tmp.resolve(".idea"));
        Files.createDirectories(tmp.resolve(".claude"));
        Files.createDirectories(tmp.resolve(".github"));

        assertEquals(List.of(AgentTarget.CLAUDE, AgentTarget.COPILOT), AgentTarget.detect(tmp));
    }

    @Test
    void skillsDirIsInsideAgentDirectory() {
        assertEquals(tmp.resolve(".opencode/skills"), AgentTarget.OPENCODE.skillsDir(tmp));
    }
}
