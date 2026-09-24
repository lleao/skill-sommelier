package br.com.codelikeaboss.skillsommelier.mojo;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Frees disk space in the clone cache ({@code skillsommelier.cacheDir}). Deletes clones not fetched for
 * {@code skillsommelier.clean.olderThan} days and clones left by older plugin versions. The cache is shared by
 * all projects; a deleted source is simply cloned again the next time it is needed.
 */
@Mojo(name = "clean", aggregator = true, requiresProject = false, threadSafe = true)
public class CleanCacheMojo extends AbstractSkillSommelierMojo {

    /** Deletes clones not fetched for this many days. */
    @Parameter(property = "skillsommelier.clean.olderThan", defaultValue = "30")
    private long olderThan;

    /** Deletes the whole cache. */
    @Parameter(property = "skillsommelier.clean.all", defaultValue = "false")
    private boolean all;

    @Override
    public void execute() throws MojoExecutionException {
        List<Path> deleted;
        try {
            deleted = sourceResolver().clean(Duration.ofDays(Math.max(0, olderThan)), all);
        } catch (IOException e) {
            throw new MojoExecutionException("Could not clean " + cacheDir + ": " + e.getMessage(), e);
        }
        deleted.forEach(p -> getLog().info("Deleted " + p));
        getLog().info(deleted.isEmpty() ? "Nothing to clean in " + cacheDir : "Deleted " + deleted.size() + " cached source(s).");
    }
}
