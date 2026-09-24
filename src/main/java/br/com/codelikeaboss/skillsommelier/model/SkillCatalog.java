package br.com.codelikeaboss.skillsommelier.model;

import java.util.List;

/** The plugin's {@code <catalog>} configuration: the named skill sources a project may install from. */
public class SkillCatalog {
    private List<SkillSource> sources;

    public SkillCatalog() {}

    public List<SkillSource> getSources() { return sources; }
    public void setSources(List<SkillSource> sources) { this.sources = sources; }
}
