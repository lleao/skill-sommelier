package br.com.codelikeaboss.skillsommelier.service;

import java.util.List;

/**
 * Minimal reader for the YAML front matter of a {@code SKILL.md}: the block between the leading
 * {@code ---} lines. Only top-level scalar keys are supported, which is all a skill header needs.
 */
final class FrontMatter {

    private static final String DELIMITER = "---";

    private final List<String> lines;

    private FrontMatter(List<String> lines) {
        this.lines = lines;
    }

    /** The front-matter lines of a document (without the delimiters); empty when there is none. */
    static FrontMatter of(List<String> document) {
        if (document.isEmpty() || !isDelimiter(document.get(0))) {
            return new FrontMatter(List.of());
        }
        int end = 1;
        while (end < document.size() && !isDelimiter(document.get(end))) {
            end++;
        }
        return new FrontMatter(document.subList(1, end));
    }

    /**
     * The value of {@code key}, collapsed to one line, or an empty string. Supports plain, quoted and block
     * ({@code |}, {@code >}) values.
     */
    String get(String key) {
        String prefix = key + ":";
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith(prefix)) {
                String value = lines.get(i).substring(prefix.length()).trim();
                if (isBlockIndicator(value)) {
                    value = indentedBlockAfter(i);
                }
                return unquote(value.trim().replaceAll("\\s+", " "));
            }
        }
        return "";
    }

    private String indentedBlockAfter(int keyLine) {
        StringBuilder block = new StringBuilder();
        for (int i = keyLine + 1; i < lines.size() && isBlockContinuation(lines.get(i)); i++) {
            block.append(' ').append(lines.get(i).trim());
        }
        return block.toString();
    }

    private static boolean isDelimiter(String line) {
        return line.trim().equals(DELIMITER);
    }

    /** Nothing after the colon, or a literal/folded block indicator with optional chomping. */
    private static boolean isBlockIndicator(String value) {
        return value.isEmpty() || value.matches("[|>][+-]?");
    }

    private static boolean isBlockContinuation(String line) {
        return line.isBlank() || Character.isWhitespace(line.charAt(0));
    }

    private static String unquote(String value) {
        boolean quoted = value.length() >= 2
                && (value.startsWith("\"") && value.endsWith("\"") || value.startsWith("'") && value.endsWith("'"));
        return quoted ? value.substring(1, value.length() - 1) : value;
    }
}
