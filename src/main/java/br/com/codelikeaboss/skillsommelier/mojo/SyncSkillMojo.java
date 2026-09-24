package br.com.codelikeaboss.skillsommelier.mojo;

import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Updates the skills installed by the plugin (listed in {@code skill-sommelier.lock.json}): copies new upstream
 * versions and restores missing skill folders. Skills edited locally are kept unless {@code skillsommelier.force}
 * is set. Skills declared in the plugin's {@code <skills>} are installed when missing, and uninstalled when their
 * declaration is removed.
 *
 * <p>Bound to {@code validate} (the first phase) by default, so it runs on every build and when an IDE based
 * on m2e (Eclipse, VS Code with the Red Hat Java extension) imports the project. It never fails the build:
 * problems are logged as warnings. During builds each remote source is contacted at most once per
 * {@code skillsommelier.updateInterval} minutes (failed attempts included); when the goal is invoked directly it
 * always fetches.
 */
@Mojo(name = "sync", defaultPhase = LifecyclePhase.VALIDATE, threadSafe = true)
public class SyncSkillMojo extends AbstractSkillSommelierMojo {

    /** Skips the goal. */
    @Parameter(property = "skillsommelier.sync.skip", defaultValue = "false")
    private boolean skip;

    /** Minimum minutes between two fetches of the same source during builds. 0 always fetches. */
    @Parameter(property = "skillsommelier.updateInterval", defaultValue = "60")
    private long updateInterval;

    /** Overwrites skills that were edited locally. */
    @Parameter(property = "skillsommelier.force", defaultValue = "false")
    private boolean force;

    @Override
    public void execute() {
        if (skip) {
            getLog().info("Skill sync skipped.");
            return;
        }
        syncInstalledSkills(sourceResolver(), fetchMaxAge(updateInterval), force);
    }
}
