package br.com.codelikeaboss.skillsommelier.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

// Uses the Unix "sleep" and "seq" commands as stand-ins for git.
@DisabledOnOs(OS.WINDOWS)
class GitClientTest {

    @Test
    void commandsThatHangAreKilledAfterTimeout() {
        // "sleep" stands in for a git process stuck on the network.
        GitClient client = new GitClient("sleep", Duration.ofMillis(500));
        long start = System.nanoTime();
        GitClient.GitException e = assertThrows(GitClient.GitException.class, () -> client.run(null, null, "30"));
        assertTrue(e.getMessage().contains("timed out"), e.getMessage());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 10);
    }

    @Test
    void largeOutputDoesNotBlock() throws Exception {
        GitClient client = new GitClient("seq", Duration.ofSeconds(30));
        String out = client.run(null, null, "200000");
        assertTrue(out.endsWith("200000"));
    }
}
