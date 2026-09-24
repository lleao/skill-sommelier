package br.com.codelikeaboss.skillsommelier.mojo;

import br.com.codelikeaboss.skillsommelier.model.AgentTarget;
import br.com.codelikeaboss.skillsommelier.model.InstalledSkill;
import br.com.codelikeaboss.skillsommelier.model.SkillSource;
import br.com.codelikeaboss.skillsommelier.service.GitClient;
import br.com.codelikeaboss.skillsommelier.service.LockFile;
import br.com.codelikeaboss.skillsommelier.service.Prompter;
import br.com.codelikeaboss.skillsommelier.service.SkillInstaller;
import br.com.codelikeaboss.skillsommelier.service.SourceResolver;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Installs skills into {@code <agent dir>/skills/<skill>} of the current project.
 *
 * <p>Missing parameters are asked interactively: first the source, then the skills (paginated,
 * multi-select or all), then the target agent when more than one is present. In batch mode
 * ({@code -B}) missing parameters fail the build instead.
 */
@Mojo(name = "add", aggregator = true, requiresProject = false)
public class AddSkillMojo extends AbstractSkillSommelierMojo {

    /** Catalog source name, Git URL or local directory. Accepts {@code <source>/<skill>} as a shorthand. */
    @Parameter(property = "source")
    private String source;

    /** Skill to install. Omit it to choose interactively. */
    @Parameter(property = "skill")
    private String skill;

    /** Target agent: opencode, claude or copilot (or its directory, e.g. .claude). */
    @Parameter(property = "target")
    private String target;

    /**
     * Branch, tag or commit id to install from, for a source given by URL. It is recorded in the lock file, so
     * {@code sync} stays on it. Catalog sources are pinned with their {@code <ref>} instead.
     */
    @Parameter(property = "ref")
    private String ref;

    private final SkillInstaller installer = new SkillInstaller();

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        SourceResolver resolver = sourceResolver();
        splitShorthand(resolver);
        if (!isBlank(skill) && !SkillInstaller.isValidName(skill)) {
            throw new MojoFailureException("Invalid skill name: '" + skill + "'");
        }
        if ((isBlank(source) || isBlank(skill)) && !isInteractive()) {
            throw new MojoFailureException("You must provide both a source and a skill name in batch mode. "
                    + "Example: -Dsource=repo -Dskill=name or -Dsource=repo/name");
        }

        try {
            SkillSource skillSource = withRef(resolver, isBlank(source) ? askForSource(resolver) : resolver.find(source));
            Path skillsDir = resolveSkillsDir(resolver, skillSource);
            List<String> skills = isBlank(skill) ? askForSkills(skillsDir) : List.of(skill);

            Path projectRoot = projectRoot();
            AgentTarget agent = selectTarget(projectRoot);
            install(resolver, skillSource, skillsDir, skills, agent, projectRoot);
        } catch (Prompter.CancelledException e) {
            throw new MojoFailureException("Installation cancelled.");
        }
    }

    private SkillSource askForSource(SourceResolver resolver) throws MojoFailureException, Prompter.CancelledException {
        List<SkillSource> sources = resolver.sources();
        if (sources.isEmpty()) {
            throw new MojoFailureException("No skill sources configured in the plugin <catalog>. "
                    + "Use -Dsource=<url|dir> to install from a source outside the catalog.");
        }
        if (sources.size() == 1) {
            getLog().info("Using the only configured source: " + sources.get(0).getName());
            return sources.get(0);
        }
        return prompter().chooseOne("Skill sources", sources, AbstractSkillSommelierMojo::sourceLabel);
    }

    /** Applies {@code -Dref} to a source given by URL. */
    private SkillSource withRef(SourceResolver resolver, SkillSource skillSource) throws MojoFailureException {
        if (isBlank(ref)) {
            return skillSource;
        }
        String trimmed = ref.trim();
        if (!GitClient.isValidRef(trimmed)) {
            throw new MojoFailureException("Invalid ref: '" + ref + "'");
        }
        if (resolver.sources().contains(skillSource)) {
            throw new MojoFailureException("Source '" + skillSource.getName()
                    + "' is in the catalog; pin it with its <ref> in the plugin configuration instead of -Dref.");
        }
        if (resolver.isLocal(skillSource)) {
            getLog().warn("-Dref is ignored for local directories; they are used as they are.");
            return skillSource;
        }
        return new SkillSource(skillSource.getName(), skillSource.getUrl(), skillSource.getToken(), trimmed);
    }

    private Path resolveSkillsDir(SourceResolver resolver, SkillSource skillSource) throws MojoExecutionException {
        try {
            return resolver.skillsDir(resolver.resolve(skillSource));
        } catch (IOException e) {
            throw new MojoExecutionException("Could not resolve source '" + skillSource.getName() + "': " + e.getMessage(), e);
        }
    }

    private List<String> askForSkills(Path skillsDir) throws MojoFailureException, Prompter.CancelledException {
        List<String> available;
        try {
            available = installer.list(skillsDir);
        } catch (IOException e) {
            throw new MojoFailureException("Folder 'skills' not found in " + skillsDir.getParent(), e);
        }
        if (available.isEmpty()) {
            throw new MojoFailureException("No skills found in " + skillsDir);
        }
        return prompter().chooseMany("Skills", available, name -> label(installer, skillsDir, name));
    }

    private void install(SourceResolver resolver, SkillSource skillSource, Path skillsDir, List<String> skills,
                         AgentTarget agent, Path projectRoot) throws MojoExecutionException, MojoFailureException {
        Path targetSkillsDir = agent.skillsDir(projectRoot);
        LockFile lock = loadLock(projectRoot);
        String recordedUrl = resolver.recordedUrl(skillSource);
        String recordedRef = resolver.recordedRef(skillSource);
        String commit = resolver.commit(skillSource).orElse(null);
        boolean single = skills.size() == 1;
        List<String> failed = new ArrayList<>();

        for (String name : skills) {
            try {
                String hash = installOne(name, skillsDir, targetSkillsDir, agent);
                if (lock != null) {
                    InstalledSkill entry = new InstalledSkill(name, agent.getId(), skillSource.getName(), recordedUrl, hash);
                    entry.setRef(recordedRef);
                    entry.setCommit(commit);
                    lock.find(name, agent.getId()).ifPresent(previous -> entry.setDeclared(previous.getDeclared()));
                    lock.put(entry);
                }
            } catch (NoSuchFileException e) {
                String message = "Skill '" + name + "' not found in " + skillsDir;
                if (single) {
                    throw new MojoFailureException(message, e);
                }
                getLog().error(message);
                failed.add(name);
            } catch (IOException e) {
                String message = "Error installing skill '" + name + "': " + e.getMessage();
                if (single) {
                    throw new MojoExecutionException(message, e);
                }
                getLog().error(message);
                failed.add(name);
            }
        }

        int installed = skills.size() - failed.size();
        saveLock(lock, installed);
        if (!single) {
            getLog().info("Installed " + installed + " of " + skills.size() + " skill(s) into " + targetSkillsDir);
        }
        if (!failed.isEmpty()) {
            throw new MojoExecutionException("Failed to install: " + String.join(", ", failed));
        }
    }

    /** Copies one skill into the agent directory and returns its hash for the lock file. */
    private String installOne(String name, Path skillsDir, Path targetSkillsDir, AgentTarget agent)
            throws IOException {
        SkillInstaller.Installed installed = installer.install(skillsDir, name, targetSkillsDir);
        Path destination = installed.destination();
        if (!installed.skippedLinks().isEmpty()) {
            getLog().warn("Skill '" + name + "': symbolic links not copied: " + installed.skippedLinks());
        }
        if (!Files.isRegularFile(destination.resolve(SkillInstaller.SKILL_FILE))) {
            getLog().warn("Skill '" + name + "' has no " + SkillInstaller.SKILL_FILE + "; the agent may not load it.");
        }
        getLog().info("Installed '" + name + "' for " + agent.getId() + " to: " + destination);
        return installer.hash(destination);
    }

    private LockFile loadLock(Path projectRoot) {
        try {
            return LockFile.load(projectRoot);
        } catch (IOException e) {
            getLog().warn("Could not read " + LockFile.FILE_NAME + " (" + e.getMessage() + "); installed skills will not be tracked by sync.");
            return null;
        }
    }

    private void saveLock(LockFile lock, int installed) {
        if (lock == null || installed == 0) {
            return;
        }
        try {
            lock.save();
            getLog().info("Recorded in " + LockFile.FILE_NAME + " (kept up to date by the 'sync' goal).");
        } catch (IOException e) {
            getLog().warn("Could not write " + LockFile.FILE_NAME + ": " + e.getMessage());
        }
    }

    /** {@code -Dsource=<catalog name>/<skill>}. URLs and paths are never split, so they can open the skill menu. */
    private void splitShorthand(SourceResolver resolver) {
        if (!isBlank(skill) || source == null || !source.contains("/")) {
            return;
        }
        int lastSlash = source.lastIndexOf('/');
        String prefix = source.substring(0, lastSlash);
        if (resolver.sources().stream().anyMatch(s -> prefix.equals(s.getName()))) {
            skill = source.substring(lastSlash + 1);
            source = prefix;
        }
    }

    private AgentTarget selectTarget(Path projectRoot) throws MojoFailureException, Prompter.CancelledException {
        if (!isBlank(target)) {
            return parseTarget(target);
        }

        List<AgentTarget> detected = AgentTarget.detect(projectRoot);
        if (detected.isEmpty()) {
            getLog().info("No agent directory found. Defaulting to " + AgentTarget.OPENCODE.getDirectory()
                    + " (use -Dtarget=" + targetChoices() + " to choose).");
            return AgentTarget.OPENCODE;
        }
        if (detected.size() == 1) {
            return detected.get(0);
        }
        if (!isInteractive()) {
            throw new MojoFailureException("Multiple agent directories found " + directories(detected)
                    + ". Choose one with -Dtarget=<" + targetChoices() + ">.");
        }
        return prompter().chooseOne("Install into", detected, t -> t.getDirectory() + " (" + t.getId() + ")");
    }

    /** {@code opencode|claude|copilot} */
    private static String targetChoices() {
        return AgentTarget.validValues().replace(", ", "|");
    }

    private static String directories(List<AgentTarget> targets) {
        return targets.stream().map(AgentTarget::getDirectory).toList().toString();
    }
}
