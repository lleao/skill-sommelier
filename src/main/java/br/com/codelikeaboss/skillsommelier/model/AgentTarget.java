package br.com.codelikeaboss.skillsommelier.model;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/** Coding agents supported as installation targets, and the project directory each one reads skills from. */
public enum AgentTarget {
    OPENCODE("opencode", ".opencode"),
    CLAUDE("claude", ".claude"),
    COPILOT("copilot", ".github");

    private final String id;
    private final String directory;

    AgentTarget(String id, String directory) {
        this.id = id;
        this.directory = directory;
    }

    public String getId() { return id; }
    public String getDirectory() { return directory; }

    /** {@code <projectRoot>/<agent dir>/skills} */
    public Path skillsDir(Path projectRoot) {
        return projectRoot.resolve(directory).resolve("skills");
    }

    /** Accepts the agent id ({@code claude}) or its directory name ({@code .claude}), case-insensitive. */
    public static Optional<AgentTarget> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(t -> t.id.equals(v) || t.directory.equals(v))
                .findFirst();
    }

    /** Agents whose directory already exists in the project root. */
    public static List<AgentTarget> detect(Path projectRoot) {
        List<AgentTarget> found = new ArrayList<>();
        for (AgentTarget t : values()) {
            if (Files.isDirectory(projectRoot.resolve(t.directory))) {
                found.add(t);
            }
        }
        return found;
    }

    public static String validValues() {
        return Arrays.stream(values()).map(AgentTarget::getId).collect(Collectors.joining(", "));
    }
}
