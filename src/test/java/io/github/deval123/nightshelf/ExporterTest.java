package io.github.deval123.nightshelf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExporterTest {

    @TempDir
    Path root;

    private Path library() throws IOException {
        return Files.createDirectories(root.resolve("library"));
    }

    private Path outDir() throws IOException {
        return Files.createDirectories(root.resolve("out"));
    }

    private SearchService.Hit hit(String key, String description) {
        return new SearchService.Hit(key, 0.03, 0.5, 12.5, description, root.resolve("library/clips/" + key + ".mp4"),
                Map.of("chan2", "approved", "chan1", "rejected"), List.of("chan1", "chan2"));
    }

    private List<String> filesIn(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void refusesATargetInsideTheLibrary() throws Exception {
        Path lib = library();
        Files.createDirectories(lib.resolve("sub"));

        for (Path out : List.of(lib.resolve("list.csv"), lib.resolve("sub/list.csv"), lib.resolve("sub/../list.csv"))) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> Exporter.write(out, lib, false, Exporter.Format.CSV, "q", List.of()));
            assertTrue(e.getMessage().contains("inside the clip library"), e.getMessage());
        }
        assertEquals(List.of("sub"), filesIn(lib));
    }

    @Test
    void refusesATargetInsideTheLibraryReachedThroughALinkedDirectory() throws Exception {
        Path lib = library();
        Path link = root.resolve("out/linked");
        Files.createDirectories(root.resolve("out"));
        Files.createSymbolicLink(link, lib);

        assertThrows(IllegalArgumentException.class,
                () -> Exporter.write(link.resolve("list.csv"), lib, true, Exporter.Format.CSV, "q", List.of()));
        assertEquals(List.of(), filesIn(lib));
    }

    @Test
    void refusesToOverwriteWithoutForceAndOverwritesWithIt() throws Exception {
        Path lib = library();
        Path out = outDir().resolve("list.md");
        Files.writeString(out, "mine");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Exporter.write(out, lib, false, Exporter.Format.MD, "q", List.of(hit("a", "x"))));
        assertTrue(e.getMessage().contains("--force"), e.getMessage());
        assertEquals("mine", Files.readString(out));

        Exporter.write(out, lib, true, Exporter.Format.MD, "q", List.of(hit("a", "x")));
        assertTrue(Files.readString(out).contains("| a |"));
        assertEquals(List.of("list.md"), filesIn(out.getParent()), "no temporary file is left");
    }

    @Test
    void neverFollowsASymlinkAtTheTarget() throws Exception {
        Path lib = library();
        Path victim = root.resolve("victim.txt");
        Files.writeString(victim, "precious");
        Path link = outDir().resolve("list.csv");
        Files.createSymbolicLink(link, victim);

        for (boolean force : new boolean[] {false, true}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> Exporter.write(link, lib, force, Exporter.Format.CSV, "q", List.of(hit("a", "x"))));
            assertTrue(e.getMessage().contains("symbolic link"), e.getMessage());
        }
        assertEquals("precious", Files.readString(victim));
        assertTrue(Files.isSymbolicLink(link));

        Path dangling = outDir().resolve("dangling.csv");
        Files.createSymbolicLink(dangling, root.resolve("nowhere.txt"));
        assertThrows(IllegalArgumentException.class,
                () -> Exporter.write(dangling, lib, true, Exporter.Format.CSV, "q", List.of()));
        assertFalse(Files.exists(root.resolve("nowhere.txt")));
    }

    @Test
    void refusesADirectoryOrAMissingParent() throws Exception {
        Path lib = library();
        Path dir = Files.createDirectories(outDir().resolve("adir"));

        assertThrows(IllegalArgumentException.class,
                () -> Exporter.write(dir, lib, true, Exporter.Format.CSV, "q", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> Exporter.write(root.resolve("missing/list.csv"), lib, false, Exporter.Format.CSV, "q", List.of()));
    }

    @Test
    void createsTheFileForTheOwnerOnly() throws Exception {
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return;
        }
        Path out = outDir().resolve("list.json");

        Exporter.write(out, library(), false, Exporter.Format.JSON, "q", List.of(hit("a", "x")));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(out)));

        Files.setPosixFilePermissions(out, PosixFilePermissions.fromString("rw-r--r--"));
        Exporter.write(out, library(), true, Exporter.Format.JSON, "q", List.of(hit("a", "x")));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(out)));
    }

    @Test
    void writesOnlyTextAndTouchesNoClip() throws Exception {
        Path lib = library();
        Path clip = lib.resolve("clips/a.mp4");
        Files.createDirectories(clip.getParent());
        Files.writeString(clip, "video bytes");

        Exporter.write(outDir().resolve("list.csv"), lib, false, Exporter.Format.CSV, "q", List.of(hit("a", "x")));

        assertEquals("video bytes", Files.readString(clip));
        assertEquals(List.of("clips"), filesIn(lib));
        assertEquals(List.of("list.csv"), filesIn(root.resolve("out")));
    }

    @Test
    void csvHasAHeaderAndOneRowPerHit() {
        String csv = Exporter.render(Exporter.Format.CSV, "q", List.of(hit("src/a", "A dog")));

        String[] lines = csv.split("\n");
        assertEquals("key,path,duration_s,description,status,used_on", lines[0]);
        assertEquals("src/a," + root.resolve("library/clips/src/a.mp4") + ",12.5,A dog,chan1=rejected chan2=approved,\"chan1,chan2\"",
                lines[1]);
    }

    @Test
    void csvCellsAreQuotedAndFormulasAreDefused() {
        assertEquals("plain", Exporter.csvCell("plain"));
        assertEquals("\"a,b\"", Exporter.csvCell("a,b"));
        assertEquals("\"say \"\"hi\"\"\"", Exporter.csvCell("say \"hi\""));
        assertEquals("\"two\nlines\"", Exporter.csvCell("two\nlines"));
        assertEquals("'=1+1", Exporter.csvCell("=1+1"));
        assertEquals("'+1", Exporter.csvCell("+1"));
        assertEquals("'-2", Exporter.csvCell("-2"));
        assertEquals("'@SUM(A1)", Exporter.csvCell("@SUM(A1)"));
        assertEquals("\"'=HYPERLINK(\"\"http://x\"\",\"\"y\"\")\"", Exporter.csvCell("=HYPERLINK(\"http://x\",\"y\")"));
        assertEquals("'\tcmd", Exporter.csvCell("\tcmd"));
        assertEquals("a=b", Exporter.csvCell("a=b"));
        assertEquals("", Exporter.csvCell(""));
    }

    @Test
    void csvDefusesHostileDescriptionsAndKeys() {
        String csv = Exporter.render(Exporter.Format.CSV, "q", List.of(hit("-evil", "=cmd|' /C calc'!A0")));

        assertTrue(csv.contains("'-evil,"), csv);
        assertTrue(csv.contains(",'=cmd|' /C calc'!A0,"), csv);
    }

    @Test
    void markdownEscapesPipesAndNewlines() {
        assertEquals("a\\|b", Exporter.mdCell("a|b"));
        assertEquals("a b c", Exporter.mdCell("a\nb\r\nc").replace("  ", " "));
        assertEquals("\\\\\\|", Exporter.mdCell("\\|"));
        assertEquals("\\<b\\>\\`x\\`", Exporter.mdCell("<b>`x`"));
        assertEquals("a b", Exporter.mdCell("a\u001bb"));

        String md = Exporter.render(Exporter.Format.MD, "x|y\nz", List.of(hit("src/a", "one | two\nthree")));
        assertTrue(md.contains("Query: x\\|y z"), md);
        assertTrue(md.contains("| one \\| two three |"), md);
        for (String line : md.split("\n")) {
            if (line.startsWith("| 1 ")) {
                assertEquals(8, line.replace("\\|", "").chars().filter(c -> c == '|').count(), line);
            }
        }
    }

    @Test
    void jsonIsBuiltWithJackson() throws Exception {
        String json = Exporter.render(Exporter.Format.JSON, "a \"q\"\n",
                List.of(hit("src/a", "line1\nline2 \"quoted\" \u001b[2J"), hit("src/b", "plain")));

        JsonNode doc = new ObjectMapper().readTree(json);
        assertEquals("a \"q\"\n", doc.get("query").asText());
        assertEquals(2, doc.get("count").asInt());
        JsonNode first = doc.get("results").get(0);
        assertEquals("src/a", first.get("key").asText());
        assertEquals("line1\nline2 \"quoted\" \u001b[2J", first.get("description").asText());
        assertEquals(12.5, first.get("duration").asDouble());
        assertEquals("rejected", first.get("status").get("chan1").asText());
        assertEquals("chan2", first.get("usedOn").get(1).asText());
        assertFalse(json.contains("\u001b"), "control characters are escaped");
    }

    @Test
    void anEmptyShortlistIsStillAValidFile() throws Exception {
        assertEquals("key,path,duration_s,description,status,used_on\n", Exporter.render(Exporter.Format.CSV, "q", List.of()));
        assertEquals(0, new ObjectMapper().readTree(Exporter.render(Exporter.Format.JSON, "q", List.of())).get("count").asInt());
        assertTrue(Exporter.render(Exporter.Format.MD, "q", List.of()).contains("| # | Key |"));
    }
}
