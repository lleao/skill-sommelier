package br.com.codelikeaboss.skillsommelier.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** One skill installed by the plugin, as recorded in {@code skill-sommelier.lock.json}. Never holds a token. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class InstalledSkill {
    private String name;
    private String target;
    private String source;
    private String url;
    private String hash;
    private String ref;
    private String commit;
    private Boolean declared;

    public InstalledSkill() {}

    public InstalledSkill(String name, String target, String source, String url, String hash) {
        this.name = name;
        this.target = target;
        this.source = source;
        this.url = url;
        this.hash = hash;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    /** Agent id, see {@link AgentTarget#getId()}. */
    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }

    /** Catalog source name (or the raw URL/path when installed from outside the catalog). */
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    /** SHA-256 of the skill's contents when it was installed or last synced. */
    public String getHash() { return hash; }
    public void setHash(String hash) { this.hash = hash; }

    /** Branch, tag or commit id the source is pinned to; null for the default branch and local sources. */
    public String getRef() { return ref; }
    public void setRef(String ref) { this.ref = ref; }

    /** Commit of the source the installed content was copied from; null for local sources. */
    public String getCommit() { return commit; }
    public void setCommit(String commit) { this.commit = commit; }

    /** True when installed from the plugin's {@code <skills>}; null (omitted from the file) otherwise. */
    public Boolean getDeclared() { return declared; }
    public void setDeclared(Boolean declared) { this.declared = Boolean.TRUE.equals(declared) ? Boolean.TRUE : null; }

    public boolean fromDeclaration() {
        return Boolean.TRUE.equals(declared);
    }

    public boolean sameSlot(InstalledSkill other) {
        return name.equals(other.name) && target.equals(other.target);
    }
}
