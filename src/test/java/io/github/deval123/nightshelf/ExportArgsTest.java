package io.github.deval123.nightshelf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExportArgsTest {

    @Test
    void parsesExportOptionsAndSearchOptions() {
        ExportArgs a = ExportArgs.parse(List.of("rainy city", "--format", "CSV", "--out", "list.csv", "--force",
                "--channel", "c1", "--status", "unused", "--limit", "5", "--min-similarity", "0.4"));

        assertEquals("rainy city", a.search().query());
        assertEquals(Exporter.Format.CSV, a.format());
        assertEquals(Path.of("list.csv"), a.out());
        assertTrue(a.force());
        assertEquals("c1", a.search().filter().channel());
        assertEquals(SearchFilter.Status.UNUSED, a.search().filter().status());
        assertEquals(5, a.search().limit());
        assertEquals(0.4, a.search().minSimilarity());
    }

    @Test
    void forceIsOffByDefault() {
        assertFalse(ExportArgs.parse(List.of("q", "--format", "md", "--out", "x.md")).force());
    }

    @Test
    void badInputIsReportedInOneLine() {
        for (List<String> bad : List.of(
                List.of("q", "--out", "x"),
                List.of("q", "--format", "md"),
                List.of("--format", "md", "--out", "x"),
                List.of("q", "--format", "xml", "--out", "x"),
                List.of("q", "--format", "md", "--out"),
                List.of("q", "--format", "md", "--out", "--force"),
                List.of("q", "--format", "md", "--out", " "),
                List.of("q", "--format", "md", "--out", "x", "--bogus", "1"),
                List.of("q", "--format", "md", "--out", "x", "--limit", "0"),
                List.of("q", "--format", "md", "--out", "x", "--status", "unused"),
                List.of("q", "r", "--format", "md", "--out", "x"))) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ExportArgs.parse(bad), bad.toString());
            assertEquals(1, e.getMessage().lines().count(), e.getMessage());
        }
    }
}
