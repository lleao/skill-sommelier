package br.com.codelikeaboss.skillsommelier.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Lists, copies, hashes and removes skill folders ({@code skills/<name>/SKILL.md}).
 *
 * <p>Symbolic links inside a skill are never followed nor copied: a skill repository could otherwise
 * point at files outside of it (e.g. {@code ~/.ssh/id_rsa}) and have them copied into the project.
 */
public class SkillInstaller {

    public static final String SKILL_FILE = "SKILL.md";

    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final Pattern INSIDE_GIT_DIR = Pattern.compile("(.*/)?\\.git/.*");

    /** Result of an installation. */
    public record Installed(Path destination, List<String> skippedLinks) {}

    /** Skill names (real sub-directories, not links) found in a {@code skills/} directory, sorted. */
    public List<String> list(Path skillsDir) throws IOException {
        if (!Files.isDirectory(skillsDir)) {
            throw new NoSuchFileException(skillsDir.toString(), null, "folder 'skills' not found");
        }
        try (Stream<Path> children = Files.list(skillsDir)) {
            return children.filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
                    .map(p -> p.getFileName().toString())
                    .filter(SkillInstaller::isValidName)
                    .sorted()
                    .toList();
        }
    }

    /**
     * The {@code description} from the skill's {@code SKILL.md} front matter, collapsed to one line.
     * Returns an empty string when there is none.
     */
    public String description(Path skillDir) {
        Path file = skillDir.resolve(SKILL_FILE);
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                return "";
            }
            return FrontMatter.of(Files.readAllLines(file)).get("description");
        } catch (IOException e) {
            return "";
        }
    }

    /** Rejects names that could escape the skills directory, such as {@code ../foo}. */
    public static boolean isValidName(String skill) {
        return skill != null && VALID_NAME.matcher(skill).matches() && !skill.contains("..");
    }

    /**
     * Copies {@code <skillsDir>/<skill>} to {@code <targetSkillsDir>/<skill>}. An existing copy is replaced
     * entirely, so files removed from the source also disappear from the project. Symbolic links are skipped
     * and reported in the result.
     */
    public Installed install(Path skillsDir, String skill, Path targetSkillsDir) throws IOException {
        Path source = skillPath(skillsDir, skill);
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new NoSuchFileException(source.toString(), null, "skill '" + skill + "' not found");
        }
        Path destination = targetSkillsDir.resolve(skill);
        FileTrees.delete(destination);
        List<String> skippedLinks = FileTrees.copyRegularFiles(source, destination);
        return new Installed(destination, skippedLinks);
    }

    /** Deletes an installed skill. Returns false if it was not there. */
    public boolean remove(Path targetSkillsDir, String skill) throws IOException {
        Path installed = skillPath(targetSkillsDir, skill);
        if (!Files.exists(installed, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        FileTrees.delete(installed);
        return true;
    }

    /**
     * SHA-256 over the relative paths and contents of every regular file (sorted; links and {@code .git}
     * excluded). Identical for a skill in its source and in the project, so it detects both upstream updates
     * and local edits. Line endings of text files are normalised to LF, so checkouts with
     * {@code core.autocrlf} produce the same hash on every OS.
     */
    public String hash(Path dir) throws IOException {
        MessageDigest digest = Sha256.newDigest();
        for (String relativePath : hashedFiles(dir)) {
            digest.update(relativePath.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(normalizeLineEndings(Files.readAllBytes(dir.resolve(relativePath))));
            digest.update((byte) 0);
        }
        return "sha256:" + Sha256.hex(digest);
    }

    /** Relative paths of the regular files that make up a skill, outside {@code .git}, sorted. */
    private static List<String> hashedFiles(Path dir) throws IOException {
        try (Stream<Path> tree = Files.walk(dir)) {
            return tree.filter(f -> Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS))
                    .map(f -> FileTrees.relativePath(dir, f))
                    .filter(path -> !INSIDE_GIT_DIR.matcher(path).matches())
                    .sorted()
                    .toList();
        }
    }

    /** CRLF to LF for text content; binary content (anything with a NUL byte) is left untouched. */
    static byte[] normalizeLineEndings(byte[] content) {
        if (isBinary(content)) {
            return content;
        }
        byte[] out = new byte[content.length];
        int length = 0;
        for (int i = 0; i < content.length; i++) {
            boolean crBeforeLf = content[i] == '\r' && i + 1 < content.length && content[i + 1] == '\n';
            if (!crBeforeLf) {
                out[length++] = content[i];
            }
        }
        return length == content.length ? content : Arrays.copyOf(out, length);
    }

    private static boolean isBinary(byte[] content) {
        for (byte b : content) {
            if (b == 0) {
                return true;
            }
        }
        return false;
    }

    private static Path skillPath(Path dir, String skill) {
        if (!isValidName(skill)) {
            throw new IllegalArgumentException("Invalid skill name: '" + skill + "'");
        }
        return dir.resolve(skill);
    }
}
