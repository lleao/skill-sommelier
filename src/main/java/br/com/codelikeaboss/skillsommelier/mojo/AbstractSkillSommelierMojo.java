package br.com.codelikeaboss.skillsommelier.mojo;

import br.com.codelikeaboss.skillsommelier.model.AgentTarget;
import br.com.codelikeaboss.skillsommelier.model.DeclaredSkill;
import br.com.codelikeaboss.skillsommelier.model.InstalledSkill;
import br.com.codelikeaboss.skillsommelier.model.SkillCatalog;
import br.com.codelikeaboss.skillsommelier.model.SkillSource;
import br.com.codelikeaboss.skillsommelier.service.GitClient;
import br.com.codelikeaboss.skillsommelier.service.LockFile;
import br.com.codelikeaboss.skillsommelier.service.Prompter;
import br.com.codelikeaboss.skillsommelier.service.SkillInstaller;
import br.com.codelikeaboss.skillsommelier.service.SkillSync;
import br.com.codelikeaboss.skillsommelier.service.SourceResolver;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** Configuration and helpers shared by all goals. */
public abstract class AbstractSkillSommelierMojo extends AbstractMojo {

    private static final int DESCRIPTION_MAX = 70;

    /**
     * Skill sources: {@code <sources><source><name/><url/><token/><ref/></source></sources>}. The optional
     * {@code ref} (branch, tag or commit id) pins a Git source; without it the default branch is followed.
     */
    @Parameter
    protected SkillCatalog catalog;

    /**
     * Skills this project must have: {@code <skills><skill><name/><source/><target/></skill></skills>}.
     * {@code sync} installs the missing ones and uninstalls the ones whose declaration was removed.
     * {@code source} is a catalog name, Git URL or local directory, and may be omitted when the catalog has a
     * single source; {@code target} takes one or more agents separated by commas (e.g. {@code claude,copilot}).
     */
    @Parameter
    protected List<DeclaredSkill> skills;

    /** Where remote sources are cloned. */
    @Parameter(property = "skillsommelier.cacheDir", defaultValue = "${user.home}/.skill-sommelier/cache")
    protected File cacheDir;

    /** Maximum duration of a single git command, in seconds. */
    @Parameter(property = "skillsommelier.gitTimeout", defaultValue = "300")
    protected long gitTimeout;

    /** Items per page in the interactive menus. */
    @Parameter(property = "skillsommelier.pageSize", defaultValue = "10")
    protected int pageSize;

    @Parameter(defaultValue = "${project.basedir}", readonly = true)
    private File basedir;

    @Parameter(defaultValue = "${session}", readonly = true)
    protected MavenSession session;

    @Parameter(defaultValue = "${mojoExecution}", readonly = true)
    private MojoExecution mojoExecution;

    private Prompter prompter;

    /**
     * The project directory; outside a Maven project (no pom.xml) the directory Maven was started in.
     * Relative local sources, agent directories and the lock file are resolved against it.
     */
    protected Path projectRoot() {
        if (basedir != null) {
            return basedir.toPath().toAbsolutePath().normalize();
        }
        if (session != null && session.getExecutionRootDirectory() != null) {
            return Paths.get(session.getExecutionRootDirectory()).toAbsolutePath().normalize();
        }
        return Paths.get("").toAbsolutePath();
    }

    protected SourceResolver sourceResolver() {
        return new SourceResolver(catalog, cacheDir.toPath(), projectRoot(),
                new GitClient(Duration.ofSeconds(Math.max(1, gitTimeout))), getLog());
    }

    /**
     * How old a source clone may be before it is fetched again: 0 when the goal is invoked directly from the
     * command line, {@code updateInterval} minutes when it runs as part of a build.
     */
    protected Duration fetchMaxAge(long updateInterval) {
        boolean invokedDirectly = mojoExecution != null && "default-cli".equals(mojoExecution.getExecutionId());
        return invokedDirectly ? Duration.ZERO : Duration.ofMinutes(Math.max(0, updateInterval));
    }

    protected boolean isInteractive() {
        return session != null && session.getRequest().isInteractiveMode();
    }

    protected boolean isOffline() {
        return session != null && session.isOffline();
    }

    protected Prompter prompter() {
        if (prompter == null) {
            prompter = new Prompter(System.in, System.out, pageSize);
        }
        return prompter;
    }

    /** {@code name  [url]}, or {@code name  [url @ ref]} for a pinned source. */
    protected static String sourceLabel(SkillSource source) {
        return source.getName() + "  [" + source.getUrl() + (source.getRef() == null ? "" : " @ " + source.getRef()) + "]";
    }

    /** {@code name - description} (description from SKILL.md, shortened) for menus and listings. */
    protected static String label(SkillInstaller installer, Path skillsDir, String name) {
        String description = installer.description(skillsDir.resolve(name));
        if (description.isEmpty()) {
            return name;
        }
        if (description.length() > DESCRIPTION_MAX) {
            description = description.substring(0, DESCRIPTION_MAX - 3) + "...";
        }
        return name + " - " + description;
    }

    protected static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Parses a {@code -Dtarget} value, failing with the list of valid values when it is unknown. */
    protected static AgentTarget parseTarget(String value) throws MojoFailureException {
        return AgentTarget.parse(value).orElseThrow(() -> new MojoFailureException(
                "Unknown target '" + value + "'. Valid values: " + AgentTarget.validValues()));
    }

    /**
     * Syncs the skills declared in {@code <skills>} and recorded in the project's lock file, and logs the
     * outcome. Never throws: problems are reported as warnings. Does nothing when there is neither.
     */
    protected void syncInstalledSkills(SourceResolver resolver, Duration maxAge, boolean force) {
        Path root = projectRoot();
        boolean hasLock = LockFile.exists(root);
        if (!hasLock && (skills == null || skills.isEmpty())) {
            getLog().debug("No " + LockFile.FILE_NAME + " in " + root + " and no <skills>; nothing to sync.");
            return;
        }
        LockFile lock;
        try {
            lock = LockFile.load(root);
        } catch (IOException e) {
            getLog().warn("Could not read " + LockFile.FILE_NAME + ": " + e.getMessage());
            return;
        }

        List<SkillSync.Result> results = new SkillSync(resolver, new SkillInstaller(), getLog())
                .sync(root, lock, skills, maxAge, isOffline(), force);
        if (results.isEmpty()) {
            return;
        }
        results.forEach(this::report);
        if (hasLock || !lock.skills().isEmpty()) {
            try {
                lock.saveIfChanged();
            } catch (IOException e) {
                getLog().warn("Could not update " + LockFile.FILE_NAME + ": " + e.getMessage());
            }
        }
        getLog().info("Skill sync: " + summary(results));
    }

    /** Counts per outcome, e.g. {@code 2 up to date, 1 updated}. */
    protected static String summary(List<SkillSync.Result> results) {
        Map<SkillSync.Outcome, Long> counts = results.stream()
                .collect(Collectors.groupingBy(SkillSync.Result::outcome,
                        () -> new EnumMap<>(SkillSync.Outcome.class), Collectors.counting()));
        return counts.entrySet().stream()
                .map(e -> e.getValue() + " " + e.getKey().name().toLowerCase(Locale.ROOT).replace('_', ' '))
                .collect(Collectors.joining(", "));
    }

    protected void report(SkillSync.Result result) {
        InstalledSkill entry = result.skill();
        String skill = "'" + entry.getName() + "' (" + entry.getTarget() + ")";
        switch (result.outcome()) {
            case UP_TO_DATE -> getLog().debug("Skill " + skill + " is up to date.");
            case UPDATED -> getLog().info("Updated skill " + skill + " from " + entry.getSource() + ".");
            case RESTORED -> getLog().info("Restored missing skill " + skill + " from " + entry.getSource()
                    + ". To uninstall it, use the 'remove' goal.");
            case INSTALLED -> getLog().info("Installed declared skill " + skill + " from " + entry.getSource() + ".");
            case UNINSTALLED -> getLog().info("Uninstalled skill " + skill + ": no longer declared in <skills>.");
            default -> getLog().warn("Skill " + skill + ": " + result.detail());
        }
    }
}
