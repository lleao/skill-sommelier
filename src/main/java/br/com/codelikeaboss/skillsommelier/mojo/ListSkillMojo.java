package br.com.codelikeaboss.skillsommelier.mojo;

import br.com.codelikeaboss.skillsommelier.model.InstalledSkill;
import br.com.codelikeaboss.skillsommelier.model.SkillSource;
import br.com.codelikeaboss.skillsommelier.service.LockFile;
import br.com.codelikeaboss.skillsommelier.service.SkillInstaller;
import br.com.codelikeaboss.skillsommelier.service.SourceResolver;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Lists the skills published by every source in the catalog, with their descriptions, and marks the ones
 * already installed in this project (per {@code skill-sommelier.lock.json}).
 */
@Mojo(name = "list", aggregator = true, threadSafe = true)
public class ListSkillMojo extends AbstractSkillSommelierMojo {

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        SourceResolver resolver = sourceResolver();
        if (resolver.sources().isEmpty()) {
            throw new MojoFailureException("No skill sources configured in the plugin <catalog>.");
        }

        List<InstalledSkill> installed = installedSkills();
        SkillInstaller installer = new SkillInstaller();
        int failures = 0;

        getLog().info("Available Skills:");
        for (SkillSource source : resolver.sources()) {
            getLog().info("Source: " + sourceLabel(source));
            try {
                Path skillsDir = resolver.skillsDir(resolver.resolve(source));
                List<String> skills = installer.list(skillsDir);
                if (skills.isEmpty()) {
                    getLog().warn("  [!] No skills found in " + skillsDir);
                }
                for (String name : skills) {
                    getLog().info("  -> " + label(installer, skillsDir, name) + installedMarker(installed, source, name));
                }
            } catch (IOException e) {
                failures++;
                getLog().error("  [!] Error processing source '" + source.getName() + "': " + e.getMessage());
            }
        }

        if (failures > 0) {
            throw new MojoExecutionException(failures + " of " + resolver.sources().size()
                    + " source(s) could not be read. See the errors above.");
        }
    }

    private List<InstalledSkill> installedSkills() {
        try {
            return LockFile.load(projectRoot()).skills();
        } catch (IOException e) {
            getLog().warn("Could not read " + LockFile.FILE_NAME + ": " + e.getMessage());
            return List.of();
        }
    }

    private static String installedMarker(List<InstalledSkill> installed, SkillSource source, String name) {
        String targets = installed.stream()
                .filter(s -> name.equals(s.getName()) && source.getName().equals(s.getSource()))
                .map(InstalledSkill::getTarget)
                .collect(Collectors.joining(", "));
        return targets.isEmpty() ? "" : "  [installed: " + targets + "]";
    }
}
