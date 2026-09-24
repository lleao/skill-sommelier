package br.com.codelikeaboss.skillsommelier.mojo;

import br.com.codelikeaboss.skillsommelier.model.AgentTarget;
import br.com.codelikeaboss.skillsommelier.model.InstalledSkill;
import br.com.codelikeaboss.skillsommelier.service.LockFile;
import br.com.codelikeaboss.skillsommelier.service.Prompter;
import br.com.codelikeaboss.skillsommelier.service.SkillInstaller;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Uninstalls skills: deletes their folders and removes them from {@code skill-sommelier.lock.json}, so
 * {@code sync} stops restoring them. Without {@code -Dskill}, asks which installed skills to remove.
 */
@Mojo(name = "remove", aggregator = true, requiresProject = false)
public class RemoveSkillMojo extends AbstractSkillSommelierMojo {

    /** Skill to remove. Omit it to choose interactively. */
    @Parameter(property = "skill")
    private String skill;

    /** Only remove it from this agent (opencode, claude or copilot). All agents when omitted. */
    @Parameter(property = "target")
    private String target;

    private final SkillInstaller installer = new SkillInstaller();

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        Path root = projectRoot();
        LockFile lock = loadLock(root);
        List<InstalledSkill> selected = select(candidates(lock, root));
        for (InstalledSkill entry : selected) {
            delete(entry, root);
            lock.remove(entry);
            if (entry.fromDeclaration()) {
                getLog().warn("'" + entry.getName() + "' (" + entry.getTarget() + ") is declared in the plugin's"
                        + " <skills>; remove the declaration too, or 'sync' will install it again.");
            }
        }
        saveLock(lock);
    }

    /** Lock file entries matching {@code -Dskill} and {@code -Dtarget}, when given. */
    private List<InstalledSkill> candidates(LockFile lock, Path root) throws MojoFailureException {
        String agentId = isBlank(target) ? null : parseTarget(target).getId();
        List<InstalledSkill> candidates = lock.skills().stream()
                .filter(s -> agentId == null || agentId.equals(s.getTarget()))
                .filter(s -> isBlank(skill) || skill.equals(s.getName()))
                .toList();
        if (candidates.isEmpty()) {
            throw new MojoFailureException(isBlank(skill)
                    ? "No skills installed by the plugin in " + root
                    : "Skill '" + skill + "' is not recorded in " + LockFile.FILE_NAME);
        }
        return candidates;
    }

    /** All candidates when {@code -Dskill} was given; otherwise the ones the user picks. */
    private List<InstalledSkill> select(List<InstalledSkill> candidates) throws MojoFailureException {
        if (!isBlank(skill)) {
            return candidates;
        }
        if (!isInteractive()) {
            throw new MojoFailureException("Provide -Dskill=<name> in batch mode.");
        }
        try {
            return prompter().chooseMany("Installed skills", candidates,
                    s -> s.getName() + " (" + s.getTarget() + ", from " + s.getSource() + ")");
        } catch (Prompter.CancelledException e) {
            throw new MojoFailureException("Removal cancelled.");
        }
    }

    private void delete(InstalledSkill entry, Path root) throws MojoExecutionException {
        Optional<Path> skillsDir = AgentTarget.parse(entry.getTarget()).map(t -> t.skillsDir(root));
        boolean deletable = skillsDir.isPresent() && SkillInstaller.isValidName(entry.getName());
        try {
            if (deletable && installer.remove(skillsDir.get(), entry.getName())) {
                getLog().info("Removed '" + entry.getName() + "' from " + skillsDir.get());
            } else {
                getLog().info("'" + entry.getName() + "' (" + entry.getTarget()
                        + ") was not on disk; removing it from the lock file.");
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Could not delete skill '" + entry.getName() + "': " + e.getMessage(), e);
        }
    }

    private static LockFile loadLock(Path root) throws MojoExecutionException {
        try {
            return LockFile.load(root);
        } catch (IOException e) {
            throw new MojoExecutionException("Could not read " + LockFile.FILE_NAME + ": " + e.getMessage(), e);
        }
    }

    private static void saveLock(LockFile lock) throws MojoExecutionException {
        try {
            lock.save();
        } catch (IOException e) {
            throw new MojoExecutionException("Could not write " + LockFile.FILE_NAME + ": " + e.getMessage(), e);
        }
    }
}
