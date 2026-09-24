package br.com.codelikeaboss.skillsommelier.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import br.com.codelikeaboss.skillsommelier.model.InstalledSkill;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * {@code skill-sommelier.lock.json} at the project root: the skills installed by the plugin.
 * Meant to be committed, so {@code sync} can restore and update skills on any machine.
 */
public class LockFile {

    public static final String FILE_NAME = "skill-sommelier.lock.json";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private final Path path;
    private final Content content;

    private LockFile(Path path, Content content) {
        this.path = path;
        this.content = content;
    }

    public static Path pathFor(Path projectRoot) {
        return projectRoot.resolve(FILE_NAME);
    }

    public static boolean exists(Path projectRoot) {
        return Files.exists(pathFor(projectRoot));
    }

    /** Reads the lock file, or returns an empty one if it does not exist. */
    public static LockFile load(Path projectRoot) throws IOException {
        Path path = pathFor(projectRoot);
        Content content = Files.exists(path) ? MAPPER.readValue(path.toFile(), Content.class) : new Content();
        if (content.skills == null) {
            content.skills = new ArrayList<>();
        }
        return new LockFile(path, content);
    }

    public List<InstalledSkill> skills() {
        return content.skills;
    }

    public Optional<InstalledSkill> find(String name, String target) {
        return content.skills.stream()
                .filter(s -> name.equals(s.getName()) && target.equals(s.getTarget()))
                .findFirst();
    }

    /** Adds the entry, replacing any previous one for the same skill and target. */
    public void put(InstalledSkill skill) {
        content.skills.removeIf(s -> s.sameSlot(skill));
        content.skills.add(skill);
    }

    public boolean remove(InstalledSkill skill) {
        return content.skills.removeIf(s -> s.sameSlot(skill));
    }

    public void save() throws IOException {
        Files.writeString(path, serialize());
    }

    /** Saves only when the content differs from the file on disk. Returns true if the file was written. */
    public boolean saveIfChanged() throws IOException {
        String json = serialize();
        if (Files.exists(path) && Files.readString(path).equals(json)) {
            return false;
        }
        Files.writeString(path, json);
        return true;
    }

    private String serialize() throws IOException {
        content.skills.sort(Comparator.comparing(InstalledSkill::getTarget).thenComparing(InstalledSkill::getName));
        return MAPPER.writeValueAsString(content) + System.lineSeparator();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Content {
        public int version = 1;
        public List<InstalledSkill> skills = new ArrayList<>();
    }
}
