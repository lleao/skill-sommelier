package br.com.codelikeaboss.skillsommelier.model;

import java.util.Arrays;
import java.util.List;

/**
 * A skill declared in the plugin's {@code <skills>} configuration. {@code sync} installs declared skills that
 * are missing and uninstalls the ones whose declaration was removed, like dependencies in a pom.
 */
public class DeclaredSkill {
    private String name;
    private String source;
    private String target;

    public DeclaredSkill() {}

    public DeclaredSkill(String name, String source, String target) {
        this.name = name;
        this.source = source;
        this.target = target;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    /** Catalog source name, Git URL or local directory. May be omitted when the catalog has a single source. */
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    /** Agent ids separated by commas, e.g. {@code claude,copilot}. */
    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }

    /** The agent ids in {@link #getTarget()}, trimmed; empty when there is none. */
    public List<String> targets() {
        if (target == null) {
            return List.of();
        }
        return Arrays.stream(target.split(",")).map(String::trim).filter(t -> !t.isEmpty()).toList();
    }
}
