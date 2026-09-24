package br.com.codelikeaboss.skillsommelier.service;

import br.com.codelikeaboss.skillsommelier.model.AgentTarget;
import br.com.codelikeaboss.skillsommelier.model.DeclaredSkill;
import br.com.codelikeaboss.skillsommelier.model.InstalledSkill;
import br.com.codelikeaboss.skillsommelier.model.SkillSource;
import org.apache.maven.plugin.logging.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Brings the skills recorded in the lock file, and the ones declared in the plugin's {@code <skills>}, up to
 * date with their sources.
 *
 * <p>Declarations come first: declared skills missing from the lock file are added to it, and entries that
 * came from a declaration which was removed are uninstalled. Then, for each entry: refresh the source (pulls
 * at most once per source and ref, throttled by {@code maxAge}), compare the upstream content hash with the
 * one recorded at install time and, if it changed, copy the new version into the project. Skill folders
 * missing from the project are restored (use the {@code remove} goal to uninstall a skill). Skills edited
 * locally are left alone unless {@code force} is set.
 *
 * <p>{@link #check} runs the same comparison without changing anything, for CI.
 */
public class SkillSync {

    public enum Outcome {
        UP_TO_DATE, UPDATED, RESTORED, INSTALLED, UNINSTALLED,
        /** Check only: sync would update the skill. */
        OUTDATED,
        /** Check only: sync would install the skill. */
        NOT_INSTALLED,
        /** Check only: sync would uninstall the skill, whose declaration was removed. */
        UNDECLARED,
        MODIFIED_LOCALLY, MISSING_UPSTREAM, SOURCE_UNAVAILABLE, INVALID_ENTRY;

        public boolean changedProject() {
            return this == UPDATED || this == RESTORED || this == INSTALLED || this == UNINSTALLED;
        }
    }

    /** What happened to one lock file entry. {@code detail} explains warnings and is null otherwise. */
    public record Result(InstalledSkill skill, Outcome outcome, String detail) {
        static Result of(InstalledSkill skill, Outcome outcome) {
            return new Result(skill, outcome, null);
        }
    }

    /** What is compared, and whether the project may be changed. */
    private record Mode(boolean dryRun, boolean upstream, boolean force) {}

    /** A source refreshed during this run: its local root, ref and commit, or why it is unavailable. */
    private record RefreshedSource(Path root, String ref, String commit, String error) {
        boolean isAvailable() {
            return root != null;
        }
    }

    private final SourceResolver resolver;
    private final SkillInstaller installer;
    private final Log log;

    public SkillSync(SourceResolver resolver, SkillInstaller installer, Log log) {
        this.resolver = resolver;
        this.installer = installer;
        this.log = log;
    }

    /** Syncs every lock file entry; updates {@code lock} in memory (the caller saves it). */
    public List<Result> sync(Path projectRoot, LockFile lock, Duration maxAge, boolean offline, boolean force) {
        return sync(projectRoot, lock, null, maxAge, offline, force);
    }

    /**
     * Applies the declarations, then syncs every lock file entry; updates {@code lock} in memory (the caller
     * saves it).
     *
     * @param declared the plugin's {@code <skills>}; null when not configured, so nothing is uninstalled
     */
    public List<Result> sync(Path projectRoot, LockFile lock, List<DeclaredSkill> declared, Duration maxAge,
                             boolean offline, boolean force) {
        List<Result> results = run(projectRoot, lock, declared, new Mode(false, true, force), maxAge, offline);
        // Declared skills that could not be installed are not recorded, so they are retried next time.
        lock.skills().removeIf(entry -> entry.fromDeclaration() && entry.getHash() == null);
        return results;
    }

    /**
     * Reports what {@link #sync} would change, without touching the project nor the lock file on disk.
     * Anything but {@link Outcome#UP_TO_DATE} means the project differs from what the lock file and the
     * declarations describe.
     *
     * @param upstream also compare with the sources (fetching them); false only checks the installed folders
     *                 against the lock file and the declarations
     */
    public List<Result> check(Path projectRoot, LockFile lock, List<DeclaredSkill> declared, boolean upstream,
                              Duration maxAge, boolean offline) {
        return run(projectRoot, lock, declared, new Mode(true, upstream, false), maxAge, offline);
    }

    private List<Result> run(Path projectRoot, LockFile lock, List<DeclaredSkill> declared, Mode mode,
                             Duration maxAge, boolean offline) {
        List<Result> results = new ArrayList<>();
        if (declared != null) {
            results.addAll(applyDeclarations(projectRoot, lock, declared, mode));
        }

        Map<String, RefreshedSource> refreshedBySource = new HashMap<>();
        for (InstalledSkill entry : List.copyOf(lock.skills())) {
            Optional<AgentTarget> agent = AgentTarget.parse(entry.getTarget());
            if (agent.isEmpty() || !SkillInstaller.isValidName(entry.getName()) || entry.getUrl() == null) {
                results.add(new Result(entry, Outcome.INVALID_ENTRY, "unknown target, invalid name or missing url"));
                continue;
            }
            Path targetSkills = agent.get().skillsDir(projectRoot);
            if (!mode.upstream()) {
                results.add(checkInstalled(entry, targetSkills));
                continue;
            }

            SkillSource source = resolver.find(entry.getSource(), entry.getUrl(), entry.getRef());
            RefreshedSource refreshed = refreshedBySource.computeIfAbsent(source.getUrl() + "#" + source.getRef(),
                    key -> refresh(source, maxAge, offline));
            if (!refreshed.isAvailable()) {
                results.add(new Result(entry, Outcome.SOURCE_UNAVAILABLE,
                        "source '" + source.getName() + "' unavailable: " + refreshed.error()));
                continue;
            }
            results.add(syncOne(lock, entry, refreshed, targetSkills, mode));
        }
        return results;
    }

    /**
     * Records every declared skill in {@code lock} (without a hash when it is new) and removes the entries
     * whose declaration is gone. Nothing is uninstalled while a declaration is invalid, since a typo would
     * otherwise uninstall a skill.
     */
    private List<Result> applyDeclarations(Path projectRoot, LockFile lock, List<DeclaredSkill> declared, Mode mode) {
        List<Result> results = new ArrayList<>();
        Set<String> slots = new HashSet<>();
        boolean allValid = true;

        for (DeclaredSkill declaration : declared) {
            String sourceName = isBlank(declaration.getSource()) ? onlyCatalogSource() : declaration.getSource().trim();
            if (!SkillInstaller.isValidName(declaration.getName()) || sourceName == null
                    || declaration.targets().isEmpty()) {
                allValid = false;
                results.add(new Result(asEntry(declaration, declaration.getTarget()), Outcome.INVALID_ENTRY,
                        "declared skill needs a valid <name>, a <target> and a <source> "
                                + "(optional when the catalog has a single source)"));
                continue;
            }
            SkillSource source = resolver.find(sourceName);
            for (String target : declaration.targets()) {
                Optional<AgentTarget> agent = AgentTarget.parse(target);
                if (agent.isEmpty()) {
                    allValid = false;
                    results.add(new Result(asEntry(declaration, target), Outcome.INVALID_ENTRY,
                            "unknown target '" + target + "'. Valid values: " + AgentTarget.validValues()));
                    continue;
                }
                slots.add(slot(declaration.getName(), agent.get().getId()));
                declare(lock, declaration.getName(), agent.get().getId(), source);
            }
        }

        if (!allValid) {
            return results;
        }
        for (InstalledSkill entry : List.copyOf(lock.skills())) {
            if (entry.fromDeclaration() && !slots.contains(slot(entry.getName(), entry.getTarget()))) {
                lock.remove(entry);
                results.add(undeclare(projectRoot, lock, entry, mode));
            }
        }
        return results;
    }

    /** Adds or retargets the lock file entry of a declared skill. The declaration wins over {@code add}. */
    private void declare(LockFile lock, String name, String target, SkillSource source) {
        String url = resolver.recordedUrl(source);
        InstalledSkill entry = lock.find(name, target).orElse(null);
        if (entry == null) {
            entry = new InstalledSkill(name, target, source.getName(), url, null);
            lock.put(entry);
        } else {
            entry.setSource(source.getName());
            entry.setUrl(url);
        }
        entry.setDeclared(true);
    }

    /** Uninstalls a skill whose declaration was removed, keeping its folder if it was edited locally. */
    private Result undeclare(Path projectRoot, LockFile lock, InstalledSkill entry, Mode mode) {
        if (mode.dryRun()) {
            return new Result(entry, Outcome.UNDECLARED, "no longer declared in <skills>; sync will uninstall it");
        }
        Optional<AgentTarget> agent = AgentTarget.parse(entry.getTarget());
        if (agent.isEmpty() || !SkillInstaller.isValidName(entry.getName())) {
            return Result.of(entry, Outcome.UNINSTALLED);
        }
        Path skillsDir = agent.get().skillsDir(projectRoot);
        Path installed = skillsDir.resolve(entry.getName());
        try {
            if (Files.isDirectory(installed, LinkOption.NOFOLLOW_LINKS) && !mode.force()
                    && !installer.hash(installed).equals(entry.getHash())) {
                return new Result(entry, Outcome.MODIFIED_LOCALLY, "no longer declared in <skills>; removed from "
                        + LockFile.FILE_NAME + " but its folder was kept because it has local changes");
            }
            installer.remove(skillsDir, entry.getName());
            return Result.of(entry, Outcome.UNINSTALLED);
        } catch (IOException e) {
            lock.put(entry);
            return new Result(entry, Outcome.UNDECLARED, "no longer declared, but could not be uninstalled: "
                    + e.getMessage());
        }
    }

    private RefreshedSource refresh(SkillSource source, Duration maxAge, boolean offline) {
        try {
            Path root = resolver.refresh(source, maxAge, offline);
            return new RefreshedSource(root, resolver.recordedRef(source), resolver.commit(source).orElse(null), null);
        } catch (IOException e) {
            return new RefreshedSource(null, null, null, e.getMessage());
        }
    }

    private Result syncOne(LockFile lock, InstalledSkill entry, RefreshedSource source, Path targetSkills, Mode mode) {
        String name = entry.getName();
        try {
            Path upstreamSkills = resolver.skillsDir(source.root());
            Path upstream = upstreamSkills.resolve(name);
            if (!Files.isDirectory(upstream, LinkOption.NOFOLLOW_LINKS)) {
                return new Result(entry, Outcome.MISSING_UPSTREAM, "no longer published by the source");
            }
            String upstreamHash = installer.hash(upstream);
            Path installed = targetSkills.resolve(name);

            if (!Files.isDirectory(installed)) {
                if (mode.dryRun()) {
                    return new Result(entry, Outcome.NOT_INSTALLED, "not installed; sync will install it");
                }
                Outcome outcome = entry.getHash() == null ? Outcome.INSTALLED : Outcome.RESTORED;
                copy(entry, upstreamSkills, targetSkills, upstreamHash, source);
                return Result.of(entry, outcome);
            }
            if (entry.getHash() == null) {
                return adopt(lock, entry, upstreamSkills, targetSkills, upstreamHash, source, mode);
            }

            boolean upstreamChanged = !upstreamHash.equals(entry.getHash());
            if (mode.dryRun()) {
                if (!installer.hash(installed).equals(entry.getHash())) {
                    return new Result(entry, Outcome.MODIFIED_LOCALLY, "has local changes");
                }
                if (upstreamChanged) {
                    return new Result(entry, Outcome.OUTDATED, "a newer version is available"
                            + (source.ref() == null ? "" : " at " + source.ref()));
                }
                if (!Objects.equals(entry.getRef(), source.ref())) {
                    return new Result(entry, Outcome.OUTDATED, LockFile.FILE_NAME + " records ref "
                            + entry.getRef() + " but the source uses " + source.ref());
                }
                return Result.of(entry, Outcome.UP_TO_DATE);
            }

            if (!upstreamChanged) {
                // Same content: only the recorded ref/commit may need to follow the source configuration.
                if (!Objects.equals(entry.getRef(), source.ref()) || entry.getCommit() == null) {
                    record(entry, upstreamHash, source);
                }
                return Result.of(entry, Outcome.UP_TO_DATE);
            }
            if (!mode.force() && !installer.hash(installed).equals(entry.getHash())) {
                return new Result(entry, Outcome.MODIFIED_LOCALLY,
                        "has local changes; not overwritten (use -Dskillsommelier.force=true)");
            }
            copy(entry, upstreamSkills, targetSkills, upstreamHash, source);
            return Result.of(entry, Outcome.UPDATED);
        } catch (IOException e) {
            return new Result(entry, Outcome.SOURCE_UNAVAILABLE, "sync failed: " + e.getMessage());
        }
    }

    /**
     * A declared skill whose folder already exists but is not in the lock file: recorded as is when it matches
     * the source, replaced only with {@code force}.
     */
    private Result adopt(LockFile lock, InstalledSkill entry, Path upstreamSkills, Path targetSkills,
                         String upstreamHash, RefreshedSource source, Mode mode) throws IOException {
        if (mode.dryRun()) {
            return new Result(entry, Outcome.NOT_INSTALLED, "folder exists but is not recorded in " + LockFile.FILE_NAME);
        }
        if (installer.hash(targetSkills.resolve(entry.getName())).equals(upstreamHash)) {
            record(entry, upstreamHash, source);
            return Result.of(entry, Outcome.INSTALLED);
        }
        if (!mode.force()) {
            lock.remove(entry);
            return new Result(entry, Outcome.MODIFIED_LOCALLY, "a different folder with this name already exists; "
                    + "not overwritten (use -Dskillsommelier.force=true)");
        }
        copy(entry, upstreamSkills, targetSkills, upstreamHash, source);
        return Result.of(entry, Outcome.INSTALLED);
    }

    /** Check without the sources: installed folders against the lock file. */
    private Result checkInstalled(InstalledSkill entry, Path targetSkills) {
        Path installed = targetSkills.resolve(entry.getName());
        if (!Files.isDirectory(installed)) {
            return new Result(entry, Outcome.NOT_INSTALLED, "not installed; sync will install it");
        }
        if (entry.getHash() == null) {
            return new Result(entry, Outcome.NOT_INSTALLED, "folder exists but is not recorded in " + LockFile.FILE_NAME);
        }
        try {
            return installer.hash(installed).equals(entry.getHash())
                    ? Result.of(entry, Outcome.UP_TO_DATE)
                    : new Result(entry, Outcome.MODIFIED_LOCALLY, "has local changes");
        } catch (IOException e) {
            return new Result(entry, Outcome.INVALID_ENTRY, "could not read " + installed + ": " + e.getMessage());
        }
    }

    private void copy(InstalledSkill entry, Path upstreamSkills, Path targetSkills, String upstreamHash,
                      RefreshedSource source) throws IOException {
        SkillInstaller.Installed copy = installer.install(upstreamSkills, entry.getName(), targetSkills);
        if (!copy.skippedLinks().isEmpty()) {
            log.warn("Skill '" + entry.getName() + "': symbolic links not copied: " + copy.skippedLinks());
        }
        log.debug("Copied " + upstreamSkills.resolve(entry.getName()) + " to " + copy.destination());
        record(entry, upstreamHash, source);
    }

    private static void record(InstalledSkill entry, String hash, RefreshedSource source) {
        entry.setHash(hash);
        entry.setRef(source.ref());
        entry.setCommit(source.commit());
    }

    private String onlyCatalogSource() {
        List<SkillSource> sources = resolver.sources();
        return sources.size() == 1 ? sources.get(0).getName() : null;
    }

    private static InstalledSkill asEntry(DeclaredSkill declaration, String target) {
        return new InstalledSkill(declaration.getName(), target, declaration.getSource(), null, null);
    }

    private static String slot(String name, String target) {
        return name + "\n" + target;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
