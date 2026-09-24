package br.com.codelikeaboss.skillsommelier.mojo;

import br.com.codelikeaboss.skillsommelier.service.LockFile;
import br.com.codelikeaboss.skillsommelier.service.SkillInstaller;
import br.com.codelikeaboss.skillsommelier.service.SkillSync;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Verifies, without changing anything, that the installed skills match {@code skill-sommelier.lock.json}, the
 * plugin's {@code <skills>} and their sources, and fails the build otherwise. Meant for CI: it fails when a skill
 * was edited locally, is missing, is not recorded in the lock file, is no longer declared, has a newer upstream
 * version, or when a source cannot be reached. Run {@code sync} and commit the result to fix it.
 *
 * <p>Not bound to any phase; add an execution (e.g. in the {@code verify} phase) or invoke it directly.
 */
@Mojo(name = "check", threadSafe = true)
public class CheckSkillMojo extends AbstractSkillSommelierMojo {

    /**
     * Also compares with the sources (fetching them), so a newer upstream version fails the check. With false,
     * only the installed folders are compared with the lock file and the declarations, without network access.
     */
    @Parameter(property = "skillsommelier.check.upstream", defaultValue = "true")
    private boolean upstream;

    /** Minimum minutes between two fetches of the same source when run as part of a build. 0 always fetches. */
    @Parameter(property = "skillsommelier.updateInterval", defaultValue = "60")
    private long updateInterval;

    /** Skips the goal. */
    @Parameter(property = "skillsommelier.check.skip", defaultValue = "false")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skill check skipped.");
            return;
        }
        Path root = projectRoot();
        LockFile lock;
        try {
            lock = LockFile.load(root);
        } catch (IOException e) {
            throw new MojoExecutionException("Could not read " + LockFile.FILE_NAME + ": " + e.getMessage(), e);
        }
        if (lock.skills().isEmpty() && (skills == null || skills.isEmpty())) {
            getLog().info("No skills recorded in " + LockFile.FILE_NAME + " nor declared in <skills>; nothing to check.");
            return;
        }

        List<SkillSync.Result> results = new SkillSync(sourceResolver(), new SkillInstaller(), getLog())
                .check(root, lock, skills, upstream, fetchMaxAge(updateInterval), isOffline());
        results.forEach(this::report);
        getLog().info("Skill check: " + summary(results));

        long problems = results.stream().filter(r -> r.outcome() != SkillSync.Outcome.UP_TO_DATE).count();
        if (problems > 0) {
            throw new MojoFailureException(problems + " skill(s) differ from " + LockFile.FILE_NAME
                    + ", <skills> or their sources. Run the 'sync' goal (with -Dskillsommelier.force=true to discard"
                    + " local edits) and commit the result.");
        }
    }
}
