package io.github.deval123.nightshelf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SearchArgsTest {

    @Test
    void parsesQueryAndOptions() {
        SearchArgs a = SearchArgs.parse(List.of("rainy city", "--channel", "c1", "--status", "unused",
                "--limit", "25", "--orientation", "portrait", "--min-duration", "4.5"));

        assertEquals("rainy city", a.query());
        assertEquals(25, a.limit());
        assertEquals("c1", a.filter().channel());
        assertEquals(SearchFilter.Status.UNUSED, a.filter().status());
        assertEquals("portrait", a.filter().orientation());
        assertEquals(4.5, a.filter().minDuration());
    }

    @Test
    void defaultsAreTenResultsAndNoRejectedClips() {
        SearchArgs a = SearchArgs.parse(List.of("q", "--channel", "c1"));

        assertEquals(10, a.limit());
        assertEquals(SearchFilter.Status.DEFAULT, a.filter().status());
        assertNull(SearchArgs.parse(List.of("q")).filter().channel());
    }

    @Test
    void rejectsBadArgumentsWithOneLine() {
        for (List<String> bad : List.of(
                List.<String>of(),
                List.of(""),
                List.of("a", "b"),
                List.of("q", "--limit", "0"),
                List.of("q", "--limit", "51"),
                List.of("q", "--limit", "x"),
                List.of("q", "--status", "unused"),
                List.of("q", "--channel", "c", "--status", "maybe"),
                List.of("q", "--min-duration", "-1"),
                List.of("q", "--min-duration", "NaN"),
                List.of("q", "--min-duration", "abc"),
                List.of("q", "--channel"),
                List.of("q", "--channel", "--status"),
                List.of("q", "--min-similarity", "-0.1"),
                List.of("q", "--min-similarity", "1.5"),
                List.of("q", "--min-similarity", "NaN"),
                List.of("q", "--min-similarity", "Infinity"),
                List.of("q", "--min-similarity", "abc"),
                List.of("q", "--min-similarity"),
                List.of("q", "--nope", "x"))) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> SearchArgs.parse(bad),
                    bad.toString());
            assertTrue(!e.getMessage().contains("\n"), e.getMessage());
        }
    }

    @Test
    void unknownOptionIsSanitizedInTheMessage() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SearchArgs.parse(List.of("q", "--\u001b[31mred")));

        assertTrue(!e.getMessage().contains("\u001b"));
    }

    @Test
    void minSimilarityIsOffByDefaultAndAcceptsTheWholeRange() {
        assertNull(SearchArgs.parse(List.of("q")).minSimilarity());
        assertEquals(0.0, SearchArgs.parse(List.of("q", "--min-similarity", "0")).minSimilarity());
        assertEquals(0.62, SearchArgs.parse(List.of("q", "--min-similarity", "0.62")).minSimilarity());
        assertEquals(1.0, SearchArgs.parse(List.of("q", "--min-similarity", "1")).minSimilarity());
    }
}
