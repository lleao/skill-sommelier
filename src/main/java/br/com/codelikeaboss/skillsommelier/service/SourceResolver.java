package br.com.codelikeaboss.skillsommelier.service;

import br.com.codelikeaboss.skillsommelier.model.SkillCatalog;
import br.com.codelikeaboss.skillsommelier.model.SkillSource;
import org.apache.maven.plugin.logging.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Turns a skill source (catalog entry, local directory or Git URL) into a local directory.
 *
 * <p>Local directories are used in place (a ref is ignored). Remote sources are cloned into
 * {@code <cacheDir>/<repo>-<hash>}, one clone per URL and ref (see {@link CachedClone}); when a clone is fetched
 * again is decided by a {@link FetchPolicy}.
 */
public class SourceResolver {

    public static final String SKILLS_DIR = "skills";

    private static final Pattern REMOTE_URL = Pattern.compile("^([a-zA-Z][a-zA-Z0-9+.-]*://|[\\w.-]+@[\\w.-]+:).*");

    private final SkillCatalog catalog;
    private final Path cacheDir;
    private final Path baseDir;
    private final GitClient git;
    private final Log log;

    /**
     * @param baseDir directory that relative local paths are resolved against (the project directory)
     */
    public SourceResolver(SkillCatalog catalog, Path cacheDir, Path baseDir, GitClient git, Log log) {
        this.catalog = catalog;
        this.cacheDir = cacheDir;
        this.baseDir = baseDir.toAbsolutePath().normalize();
        this.git = git;
        this.log = log;
    }

    public List<SkillSource> sources() {
        if (catalog == null || catalog.getSources() == null) {
            return List.of();
        }
        return catalog.getSources();
    }

    /** Looks the value up by name in the catalog; otherwise treats it as a URL or local path. */
    public SkillSource find(String nameOrUrl) {
        return findInCatalog(s -> nameOrUrl.equals(s.getName()))
                .orElseGet(() -> new SkillSource(nameOrUrl, nameOrUrl, null));
    }

    /**
     * Source recorded in the lock file. The catalog entry with the same name wins (its URL may have moved
     * and it carries the token); then a catalog entry with the same URL; otherwise the recorded URL as is.
     */
    public SkillSource find(String name, String url) {
        return find(name, url, null);
    }

    /** Like {@link #find(String, String)}; {@code ref} is used only when no catalog entry matches. */
    public SkillSource find(String name, String url, String ref) {
        return findInCatalog(s -> name != null && name.equals(s.getName()))
                .or(() -> findInCatalog(s -> url != null && url.equals(s.getUrl())))
                .orElseGet(() -> new SkillSource(name, url, null, ref));
    }

    /** True for sources that are a local directory (used in place, never cloned). */
    public boolean isLocal(SkillSource source) {
        return localDirectory(source.getUrl()).isPresent();
    }

    /**
     * URL to record in the lock file. Local directories are recorded relative to the project, so a
     * committed lock file works on other machines.
     */
    public String recordedUrl(SkillSource source) {
        return localDirectory(source.getUrl())
                .map(this::relativeToBaseDir)
                .orElse(source.getUrl());
    }

    /** Ref to record in the lock file: none for local directories, which are always used as they are. */
    public String recordedRef(SkillSource source) {
        return isLocal(source) ? null : source.getRef();
    }

    /**
     * Commit currently checked out in the cached clone of a remote source; empty for local directories, sources
     * not cloned yet and when git cannot tell.
     */
    public Optional<String> commit(SkillSource source) {
        if (isLocal(source)) {
            return Optional.empty();
        }
        CachedClone clone = new CachedClone(cachePathFor(source.getUrl(), source.getRef()));
        if (!clone.isCloned()) {
            return Optional.empty();
        }
        try {
            return Optional.of(git.headCommit(clone.dir()));
        } catch (IOException e) {
            log.debug("Could not read the commit of " + clone.dir() + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    /** Returns the local root of the source, cloning it first if it is remote and not cached yet. */
    public Path resolve(SkillSource source) throws IOException {
        return obtain(source, FetchPolicy.whenMissing());
    }

    /** Brings a remote source to its latest version, cloning it if needed. Local directories are left untouched. */
    public Path update(SkillSource source) throws IOException {
        if (isLocal(source)) {
            log.info("  [=] Local directory, nothing to update.");
        }
        return obtain(source, FetchPolicy.always());
    }

    /**
     * Like {@link #update}, but touches the network at most once per {@code maxAge}, counting failed attempts
     * too, so an unreachable source does not slow down every build. In offline mode an existing clone is used
     * as is.
     */
    public Path refresh(SkillSource source, Duration maxAge, boolean offline) throws IOException {
        return obtain(source, offline ? FetchPolicy.offline() : FetchPolicy.throttled(maxAge));
    }

    public Path skillsDir(Path sourceRoot) {
        return sourceRoot.resolve(SKILLS_DIR);
    }

    /**
     * Deletes cached clones that were not fetched for {@code olderThan} (or all of them), plus clones left by
     * older plugin versions, which have no {@code .fetched} marker. Returns the deleted clone directories.
     */
    public List<Path> clean(Duration olderThan, boolean all) throws IOException {
        List<Path> deleted = new ArrayList<>();
        Instant limit = Instant.now().minus(olderThan);
        for (Path dir : cachedCloneDirs()) {
            CachedClone clone = new CachedClone(dir);
            boolean stale = clone.withLock(() -> {
                boolean expired = clone.lastAttempt().map(last -> last.isBefore(limit)).orElse(true);
                if (all || expired) {
                    clone.delete();
                    return true;
                }
                return false;
            });
            if (stale) {
                clone.deleteLockFile();
                deleted.add(dir);
            }
        }
        return deleted;
    }

    /**
     * Cache folder for a remote URL: {@code <repo-name>-<hash>}. The hash of the full URL keeps
     * repositories with the same name (e.g. two different {@code org/skills}) apart.
     */
    Path cachePathFor(String url) {
        return cachePathFor(url, null);
    }

    /** Cache folder for a remote URL at a ref: each ref gets its own clone, so pinned sources never collide. */
    Path cachePathFor(String url, String ref) {
        String trimmed = url.replaceAll("/+$", "");
        String key = ref == null ? trimmed : trimmed + "#" + ref;
        return cacheDir.resolve(repositoryName(trimmed) + "-" + Sha256.hex(key).substring(0, 8));
    }

    /** Last path segment of the URL, without {@code .git}, reduced to file-name-safe characters. */
    private static String repositoryName(String url) {
        String name = url.substring(Math.max(url.lastIndexOf('/'), url.lastIndexOf(':')) + 1);
        if (name.endsWith(".git")) {
            name = name.substring(0, name.length() - ".git".length());
        }
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return name.isEmpty() ? "repo" : name;
    }

    /** Local directories as they are; remote sources through their cached clone, fetched as the policy says. */
    private Path obtain(SkillSource source, FetchPolicy policy) throws IOException {
        Optional<Path> local = localDirectory(source.getUrl());
        if (local.isPresent()) {
            return local.get();
        }
        CachedClone clone = new CachedClone(cachePathFor(source.getUrl(), source.getRef()));
        return clone.withLock(() -> {
            if (policy.shouldFetch(clone)) {
                fetch(source, clone);
            } else {
                log.debug("Using cached repository " + clone.dir());
            }
            return clone.dir();
        });
    }

    /** Clones or fetches, recording the attempt (successful or not) for {@link FetchPolicy#throttled}. */
    private void fetch(SkillSource source, CachedClone clone) throws IOException {
        try {
            String output;
            if (clone.isCloned()) {
                log.info("  [#] Fetching " + describe(source) + "...");
                output = git.refresh(clone.dir(), source.getToken(), source.getRef());
            } else {
                FileTrees.delete(clone.dir()); // leftovers of an interrupted clone
                log.info("  [#] Cloning " + describe(source) + "...");
                output = git.clone(source.getUrl(), clone.dir(), source.getToken(), source.getRef());
            }
            log.debug(output);
            clone.recordSuccess();
        } catch (IOException e) {
            clone.recordFailure(firstLine(e.getMessage()));
            throw e;
        }
    }

    private Optional<SkillSource> findInCatalog(Predicate<SkillSource> matches) {
        return sources().stream().filter(matches).findFirst();
    }

    private List<Path> cachedCloneDirs() throws IOException {
        if (!Files.isDirectory(cacheDir)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(cacheDir)) {
            return children.filter(Files::isDirectory).sorted().toList();
        }
    }

    /** Relative paths are tried against the project directory first, then the working directory. */
    private Optional<Path> localDirectory(String url) {
        if (url == null || REMOTE_URL.matcher(url).matches()) {
            return Optional.empty();
        }
        try {
            Path path = Paths.get(url);
            List<Path> candidates = path.isAbsolute() ? List.of(path) : List.of(baseDir.resolve(path), path);
            return candidates.stream()
                    .filter(Files::isDirectory)
                    .map(candidate -> candidate.toAbsolutePath().normalize())
                    .findFirst();
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
    }

    private String relativeToBaseDir(Path local) {
        try {
            return FileTrees.relativePath(baseDir, local);
        } catch (IllegalArgumentException e) {
            return local.toString(); // different roots, e.g. another Windows drive
        }
    }

    private static String describe(SkillSource source) {
        return source.getRef() == null ? "latest version of " + source.getName() : source.getName() + " at " + source.getRef();
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "unknown error";
        }
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }
}
