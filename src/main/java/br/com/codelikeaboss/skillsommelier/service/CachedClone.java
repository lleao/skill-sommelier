package br.com.codelikeaboss.skillsommelier.service;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The cached clone of a remote source and the two bookkeeping files next to it:
 * <ul>
 *   <li>{@code <clone>.fetched}: its mtime is the last clone/fetch attempt, successful or not, and its content
 *       is {@code ok} or {@code failed: <reason>};</li>
 *   <li>{@code <clone>.lock}: serialises git operations across threads and processes (an IDE sync and a
 *       terminal build may run at the same time).</li>
 * </ul>
 */
final class CachedClone {

    private static final String FETCHED_SUFFIX = ".fetched";
    private static final String LOCK_SUFFIX = ".lock";
    private static final String SUCCESS = "ok";

    /** File locks are held per JVM, so threads of the same build also need an in-process lock. */
    private static final Map<Path, ReentrantLock> JVM_LOCKS = new ConcurrentHashMap<>();

    /** Work done while holding the clone's lock. */
    @FunctionalInterface
    interface LockedAction<T> {
        T run() throws IOException;
    }

    private final Path dir;

    CachedClone(Path dir) {
        this.dir = dir;
    }

    Path dir() {
        return dir;
    }

    boolean isCloned() {
        return Files.isDirectory(dir.resolve(FileTrees.GIT_DIR));
    }

    /** When the last clone/fetch was attempted; empty if never, or by a plugin version without markers. */
    Optional<Instant> lastAttempt() {
        try {
            return Optional.of(Files.getLastModifiedTime(fetchedMarker()).toInstant());
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** {@code ok} or {@code failed: <reason>} for the last attempt. */
    String lastStatus() {
        try {
            return Files.readString(fetchedMarker(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "unknown";
        }
    }

    void recordSuccess() throws IOException {
        writeMarker(SUCCESS);
    }

    void recordFailure(String reason) throws IOException {
        writeMarker("failed: " + reason);
    }

    /** Deletes the clone and its marker. The lock file is kept, since the caller is still holding it. */
    void delete() throws IOException {
        FileTrees.delete(dir);
        Files.deleteIfExists(fetchedMarker());
    }

    /** Deletes the lock file. Only call it outside {@link #withLock}. */
    void deleteLockFile() throws IOException {
        Files.deleteIfExists(sibling(LOCK_SUFFIX));
    }

    /** Runs {@code action} holding a JVM lock (other threads) and a file lock (other processes) on this clone. */
    <T> T withLock(LockedAction<T> action) throws IOException {
        Files.createDirectories(dir.getParent());
        ReentrantLock jvmLock = JVM_LOCKS.computeIfAbsent(dir, k -> new ReentrantLock());
        jvmLock.lock();
        try (FileChannel channel = FileChannel.open(sibling(LOCK_SUFFIX),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            return action.run();
        } finally {
            jvmLock.unlock();
        }
    }

    private void writeMarker(String status) throws IOException {
        Files.writeString(fetchedMarker(), status, StandardCharsets.UTF_8);
    }

    private Path fetchedMarker() {
        return sibling(FETCHED_SUFFIX);
    }

    private Path sibling(String suffix) {
        return dir.resolveSibling(dir.getFileName() + suffix);
    }
}
