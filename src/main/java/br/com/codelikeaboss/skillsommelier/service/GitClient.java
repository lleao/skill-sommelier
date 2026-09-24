package br.com.codelikeaboss.skillsommelier.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Thin wrapper around the system {@code git} binary.
 *
 * <p>Authentication tokens are passed through {@code GIT_CONFIG_*} environment variables as an
 * {@code http.extraHeader}, so they never appear in the command line, in the remote URL, or in
 * the cloned repository's {@code .git/config}.
 *
 * <p>Every command has a hard timeout, and stalled HTTP transfers / SSH connections are aborted early,
 * so an unreachable source never hangs a build.
 */
public class GitClient {

    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);

    /** Directory kept by the sparse checkout of a source. */
    private static final String SPARSE_DIR = SourceResolver.SKILLS_DIR;

    private static final Pattern VALID_REF = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9._/-]*");

    private final String executable;
    private final Duration timeout;

    public GitClient() {
        this("git", DEFAULT_TIMEOUT);
    }

    public GitClient(Duration timeout) {
        this("git", timeout);
    }

    public GitClient(String executable, Duration timeout) {
        this.executable = executable;
        this.timeout = timeout;
    }

    /**
     * Shallow, blob-less, sparse clone that only checks out {@code skills/}: large repositories
     * (images, examples, benchmarks) cost little more than the skills themselves.
     */
    public String clone(String url, Path target, String token) throws IOException {
        return clone(url, target, token, null);
    }

    /** Like {@link #clone(String, Path, String)}, then moves to {@code ref} (branch, tag or commit id) if given. */
    public String clone(String url, Path target, String token, String ref) throws IOException {
        checkRef(ref);
        String out = run(null, token, "clone", "--depth", "1", "--filter=blob:none", "--sparse", "--", url, target.toString());
        return out + System.lineSeparator()
                + (ref == null ? run(target, token, "sparse-checkout", "set", SPARSE_DIR) : refresh(target, token, ref));
    }

    /**
     * Brings the cache to the remote default branch. The cache is read-only, so a fetch + hard reset is
     * used instead of a pull: it also survives force-pushes and rewritten history upstream.
     */
    public String refresh(Path repository, String token) throws IOException {
        return refresh(repository, token, null);
    }

    /**
     * Brings the cache to {@code ref} (branch, tag or full commit id), or to the remote default branch when it
     * is null. Fetching a commit id needs a server that allows it (GitHub, GitLab and Bitbucket do).
     */
    public String refresh(Path repository, String token, String ref) throws IOException {
        checkRef(ref);
        String out = run(repository, token, "fetch", "--depth", "1", "--filter=blob:none", "origin", ref == null ? "HEAD" : ref);
        out += System.lineSeparator() + run(repository, token, "reset", "--hard", "FETCH_HEAD");
        // Also migrates caches cloned by older versions (full checkout) to the sparse layout.
        out += System.lineSeparator() + run(repository, token, "sparse-checkout", "set", SPARSE_DIR);
        return out;
    }

    /** Commit id checked out in {@code repository}. */
    public String headCommit(Path repository) throws IOException {
        return run(repository, null, "rev-parse", "HEAD").trim();
    }

    /** True for names git accepts as a branch, tag or commit id and that cannot be mistaken for an option. */
    public static boolean isValidRef(String ref) {
        return ref != null && VALID_REF.matcher(ref).matches() && !ref.contains("..") && !ref.endsWith(".lock");
    }

    private static void checkRef(String ref) throws IOException {
        if (ref != null && !isValidRef(ref)) {
            throw new IOException("invalid ref '" + ref + "'");
        }
    }

    /**
     * Runs git and returns its combined stdout/stderr.
     *
     * @throws GitException when git exits with a non-zero status or exceeds the timeout
     */
    public String run(Path workingDir, String token, String... args) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.addAll(List.of(args));

        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        if (workingDir != null) {
            pb.directory(workingDir.toFile());
        }
        configureEnvironment(pb.environment(), token);

        Process process = pb.start();
        process.getOutputStream().close();
        // Drain the output concurrently, otherwise a full pipe buffer can block the process.
        CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> readAll(process.getInputStream()));
        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while running git " + args[0], e);
        }
        if (!finished) {
            process.destroyForcibly();
            throw new GitException(args[0], -1, "timed out after " + timeout.toSeconds() + "s");
        }
        String text = output.join();
        if (process.exitValue() != 0) {
            throw new GitException(args[0], process.exitValue(), text);
        }
        return text;
    }

    private static void configureEnvironment(Map<String, String> env, String token) {
        // Never block waiting for credentials on stdin.
        env.put("GIT_TERMINAL_PROMPT", "0");
        // Abort HTTP transfers slower than 1 KB/s for 30 s.
        env.putIfAbsent("GIT_HTTP_LOW_SPEED_LIMIT", "1000");
        env.putIfAbsent("GIT_HTTP_LOW_SPEED_TIME", "30");
        // Fail fast on unreachable SSH hosts and never prompt (passphrases must come from an agent).
        env.putIfAbsent("GIT_SSH_COMMAND", "ssh -o ConnectTimeout=15 -o BatchMode=yes");
        if (token != null && !token.isEmpty()) {
            String credentials = Base64.getEncoder()
                    .encodeToString(("x-access-token:" + token).getBytes(StandardCharsets.UTF_8));
            env.put("GIT_CONFIG_COUNT", "1");
            env.put("GIT_CONFIG_KEY_0", "http.extraHeader");
            env.put("GIT_CONFIG_VALUE_0", "Authorization: Basic " + credentials);
        }
    }

    private static String readAll(InputStream in) {
        try (in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            in.transferTo(out);
            return out.toString(StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static class GitException extends IOException {
        private final int exitCode;
        private final String output;

        public GitException(String subcommand, int exitCode, String output) {
            super("git " + subcommand + " failed" + (exitCode >= 0 ? " with exit code " + exitCode : "")
                    + (output.isEmpty() ? "" : ": " + output));
            this.exitCode = exitCode;
            this.output = output;
        }

        public int getExitCode() { return exitCode; }
        public String getOutput() { return output; }
    }
}
