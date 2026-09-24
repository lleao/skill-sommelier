package br.com.codelikeaboss.skillsommelier.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class PrompterTest {

    private static final List<String> SKILLS = IntStream.rangeClosed(1, 25)
            .mapToObj(i -> String.format("skill-%02d", i))
            .collect(Collectors.toList());

    private final ByteArrayOutputStream output = new ByteArrayOutputStream();

    private Prompter prompter(String input) {
        return new Prompter(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(output, true, StandardCharsets.UTF_8), 10);
    }

    private String out() {
        return output.toString(StandardCharsets.UTF_8);
    }

    @Test
    void chooseOneByNumber() throws Exception {
        assertEquals("b", prompter("2\n").chooseOne("Sources", List.of("a", "b", "c"), Function.identity()));
    }

    @Test
    void chooseOneRejectsMultipleAndRetries() throws Exception {
        assertEquals("c", prompter("1,2\n3\n").chooseOne("Sources", List.of("a", "b", "c"), Function.identity()));
        assertTrue(out().contains("Select exactly one item."));
    }

    @Test
    void paginatesAndNumbersGlobally() throws Exception {
        List<String> chosen = prompter("n\n12\n").chooseMany("Skills", SKILLS, Function.identity());
        assertEquals(List.of("skill-12"), chosen);
        assertTrue(out().contains("page 1/3"));
        assertTrue(out().contains("page 2/3"));
        assertTrue(out().contains("[11] skill-11"));
        assertFalse(out().substring(0, out().indexOf("page 2/3")).contains("skill-11"),
                "first page must only show 10 items");
    }

    @Test
    void cannotGoBeyondFirstOrLastPage() throws Exception {
        prompter("p\nn\nn\nn\n1\n").chooseMany("Skills", SKILLS, Function.identity());
        assertTrue(out().contains("Already on the first page."));
        assertTrue(out().contains("Already on the last page."));
    }

    @Test
    void selectsListsAndRanges() throws Exception {
        List<String> chosen = prompter("1, 3 20-22\n").chooseMany("Skills", SKILLS, Function.identity());
        assertEquals(List.of("skill-01", "skill-03", "skill-20", "skill-21", "skill-22"), chosen);
    }

    @Test
    void allSelectsEverything() throws Exception {
        assertEquals(SKILLS, prompter("a\n").chooseMany("Skills", SKILLS, Function.identity()));
    }

    @Test
    void filterThenAllSelectsOnlyMatches() throws Exception {
        List<String> chosen = prompter("/skill-1\na\n").chooseMany("Skills", SKILLS, Function.identity());
        assertEquals(10, chosen.size());
        assertTrue(chosen.stream().allMatch(s -> s.startsWith("skill-1")));
    }

    @Test
    void numbersReferToFilteredView() throws Exception {
        List<String> chosen = prompter("/25\n1\n").chooseMany("Skills", SKILLS, Function.identity());
        assertEquals(List.of("skill-25"), chosen);
    }

    @Test
    void filterWithoutMatchKeepsView() throws Exception {
        List<String> chosen = prompter("/zzz\n2\n").chooseMany("Skills", SKILLS, Function.identity());
        assertEquals(List.of("skill-02"), chosen);
        assertTrue(out().contains("No match for 'zzz'."));
    }

    @Test
    void invalidInputRetries() throws Exception {
        List<String> chosen = prompter("abc\n0\n99\n5\n").chooseMany("Skills", SKILLS, Function.identity());
        assertEquals(List.of("skill-05"), chosen);
        assertTrue(out().contains("Invalid input: 'abc'."));
        assertTrue(out().contains("Out of range: 99"));
    }

    @Test
    void allIsNotOfferedForSingleChoice() {
        assertThrows(Prompter.CancelledException.class,
                () -> prompter("a\n").chooseOne("Sources", List.of("x", "y"), Function.identity()));
        assertTrue(out().contains("Invalid input: 'a'."));
    }

    @Test
    void quitOrEndOfInputCancels() {
        assertThrows(Prompter.CancelledException.class,
                () -> prompter("q\n").chooseMany("Skills", SKILLS, Function.identity()));
        assertThrows(Prompter.CancelledException.class,
                () -> prompter("").chooseMany("Skills", SKILLS, Function.identity()));
    }
}
