package br.com.codelikeaboss.skillsommelier.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Paginated console menus. Items are numbered 1..N across all pages, so a number typed on any page
 * refers to the same item.
 *
 * <p>Commands: {@code n} next page, {@code p} previous page, {@code /text} filter ({@code /} clears),
 * {@code a} all (multi-select only), {@code q} quit. Multi-select accepts lists and ranges: {@code 1,3 5-7}.
 */
public class Prompter {

    private final BufferedReader in;
    private final PrintStream out;
    private final int pageSize;

    public Prompter(InputStream in, PrintStream out, int pageSize) {
        // Not closed on purpose: closing it would close System.in for the rest of the build.
        this.in = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        this.out = out;
        this.pageSize = Math.max(1, pageSize);
    }

    public <T> T chooseOne(String title, List<T> options, Function<T, String> label) throws CancelledException {
        return choose(title, options, label, false).get(0);
    }

    public <T> List<T> chooseMany(String title, List<T> options, Function<T, String> label) throws CancelledException {
        return choose(title, options, label, true);
    }

    private <T> List<T> choose(String title, List<T> options, Function<T, String> label, boolean many)
            throws CancelledException {
        if (options.isEmpty()) {
            throw new IllegalArgumentException("Nothing to choose from");
        }
        Menu<T> menu = new Menu<>(title, options, label, many);
        while (true) {
            menu.print();
            String input = readLine();
            if (input == null || input.equalsIgnoreCase("q")) {
                throw new CancelledException();
            }
            String command = input.toLowerCase(Locale.ROOT);
            if (command.equals("n")) {
                menu.nextPage();
            } else if (command.equals("p")) {
                menu.previousPage();
            } else if (command.startsWith("/")) {
                menu.filter(input.substring(1).trim());
            } else if (many && command.equals("a")) {
                return new ArrayList<>(menu.view);
            } else {
                Optional<List<T>> selected = menu.select(input);
                if (selected.isPresent()) {
                    return selected.get();
                }
            }
        }
    }

    private String readLine() {
        try {
            String line = in.readLine();
            return line == null ? null : line.trim();
        } catch (IOException e) {
            return null;
        }
    }

    /** State of one menu while the user browses it: the (possibly filtered) view and the current page. */
    private final class Menu<T> {
        private final String title;
        private final List<T> options;
        private final Function<T, String> label;
        private final boolean many;

        private List<T> view;
        private String filter;
        private int page;

        Menu(String title, List<T> options, Function<T, String> label, boolean many) {
            this.title = title;
            this.options = options;
            this.label = label;
            this.many = many;
            this.view = options;
        }

        int pageCount() {
            return Math.max(1, (view.size() + pageSize - 1) / pageSize);
        }

        void nextPage() {
            if (page < pageCount() - 1) {
                page++;
            } else {
                out.println("Already on the last page.");
            }
        }

        void previousPage() {
            if (page > 0) {
                page--;
            } else {
                out.println("Already on the first page.");
            }
        }

        /** Shows only the options whose label contains {@code term}; an empty term clears the filter. */
        void filter(String term) {
            List<T> matches = term.isEmpty() ? options : matching(term);
            if (matches.isEmpty()) {
                out.println("No match for '" + term + "'.");
                return;
            }
            view = matches;
            filter = term.isEmpty() ? null : term;
            page = 0;
        }

        private List<T> matching(String term) {
            String needle = term.toLowerCase(Locale.ROOT);
            return options.stream()
                    .filter(option -> label.apply(option).toLowerCase(Locale.ROOT).contains(needle))
                    .toList();
        }

        void print() {
            int pages = pageCount();
            out.println();
            out.println(title + " (" + view.size() + (filter != null ? " matching '" + filter + "'" : "")
                    + (pages > 1 ? ", page " + (page + 1) + "/" + pages : "") + "):");
            int width = String.valueOf(view.size()).length();
            int from = page * pageSize;
            int to = Math.min(from + pageSize, view.size());
            for (int i = from; i < to; i++) {
                out.printf("  [%" + width + "d] %s%n", i + 1, label.apply(view.get(i)));
            }
            out.print("Choose " + String.join(" | ", hints(pages)) + ": ");
            out.flush();
        }

        private List<String> hints(int pages) {
            List<String> hints = new ArrayList<>();
            hints.add(many ? "numbers (e.g. 1,3 5-7)" : "a number");
            if (many) {
                hints.add("a = all" + (filter != null ? " matching" : ""));
            }
            if (pages > 1) {
                hints.add("n/p = next/previous page");
            }
            hints.add("/text = filter");
            hints.add("q = quit");
            return hints;
        }

        /** Parses {@code 1,3 5-7}; prints why and returns empty when the input is not a valid selection. */
        Optional<List<T>> select(String input) {
            Set<Integer> indexes = new LinkedHashSet<>();
            try {
                for (String token : input.split("[,\\s]+")) {
                    if (token.isEmpty()) {
                        continue;
                    }
                    int dash = token.indexOf('-', 1);
                    int start = Integer.parseInt(dash < 0 ? token : token.substring(0, dash));
                    int end = dash < 0 ? start : Integer.parseInt(token.substring(dash + 1));
                    if (start < 1 || end > view.size() || start > end) {
                        out.println("Out of range: " + token + " (valid: 1-" + view.size() + ").");
                        return Optional.empty();
                    }
                    for (int i = start; i <= end; i++) {
                        indexes.add(i - 1);
                    }
                }
            } catch (NumberFormatException e) {
                out.println("Invalid input: '" + input + "'.");
                return Optional.empty();
            }
            if (indexes.isEmpty() || (!many && indexes.size() > 1)) {
                out.println(many ? "Select at least one item." : "Select exactly one item.");
                return Optional.empty();
            }
            return Optional.of(indexes.stream().map(view::get).toList());
        }
    }

    /** The user typed {@code q} or closed the input. */
    public static class CancelledException extends Exception {
        public CancelledException() {
            super("Cancelled by user.");
        }
    }
}
