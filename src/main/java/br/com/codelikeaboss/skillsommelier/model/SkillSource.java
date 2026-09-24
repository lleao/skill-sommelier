package br.com.codelikeaboss.skillsommelier.model;

import java.util.Objects;

/**
 * A place skills are published: a Git URL or a local directory with a top-level {@code skills/} folder.
 * The optional token authenticates HTTPS clones and is never logged nor written to the lock file.
 * The optional ref (branch, tag or commit id) pins a Git source; without it the remote default branch is used.
 */
public class SkillSource {
    private String name;
    private String url;
    private String token;
    private String ref;

    public SkillSource() {}

    public SkillSource(String name, String url, String token) {
        this(name, url, token, null);
    }

    public SkillSource(String name, String url, String token, String ref) {
        this.name = name;
        this.url = url;
        this.token = token;
        this.ref = ref;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    /** Branch, tag or full commit id to install from; null or blank for the remote default branch. */
    public String getRef() { return ref == null || ref.isBlank() ? null : ref.trim(); }
    public void setRef(String ref) { this.ref = ref; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SkillSource that = (SkillSource) o;
        return Objects.equals(name, that.name) && Objects.equals(url, that.url) && Objects.equals(token, that.token)
                && Objects.equals(getRef(), that.getRef());
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, url, token, getRef());
    }
}
