package br.com.codelikeaboss.skillsommelier.mojo;

import br.com.codelikeaboss.skillsommelier.model.SkillSource;
import br.com.codelikeaboss.skillsommelier.service.SourceResolver;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * Fetches the latest version of the sources and then updates the skills installed in this project
 * (the same as {@code sync}). Unlike {@code sync}, failing to fetch a source fails the build.
 */
@Mojo(name = "update", aggregator = true, requiresProject = false, threadSafe = true)
public class UpdateSkillMojo extends AbstractSkillSommelierMojo {

    /** Updates only this source (catalog name or URL). All catalog sources when omitted. */
    @Parameter(property = "source")
    private String source;

    /** Overwrites installed skills that were edited locally. */
    @Parameter(property = "skillsommelier.force", defaultValue = "false")
    private boolean force;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        SourceResolver resolver = sourceResolver();
        List<SkillSource> targets = !isBlank(source) ? List.of(resolver.find(source)) : resolver.sources();
        if (targets.isEmpty()) {
            throw new MojoFailureException("No skill sources configured in the plugin <catalog>. Use -Dsource=<url> to update a single source.");
        }

        int failures = 0;
        for (SkillSource s : targets) {
            getLog().info("Source: " + sourceLabel(s));
            try {
                resolver.update(s);
            } catch (IOException e) {
                failures++;
                getLog().error("  [!] Error updating source '" + s.getName() + "': " + e.getMessage());
            }
        }

        // Sources were just fetched; a long max age keeps sync from fetching them again.
        syncInstalledSkills(resolver, Duration.ofDays(1), force);

        if (failures > 0) {
            throw new MojoExecutionException(failures + " of " + targets.size()
                    + " source(s) could not be updated. See the errors above.");
        }
    }
}
