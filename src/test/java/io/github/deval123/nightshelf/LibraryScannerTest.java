package io.github.deval123.nightshelf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LibraryScannerTest {

    @TempDir
    Path root;

    private Path fake(String relative, String content) throws IOException {
        Path p = root.resolve(relative);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
        return p;
    }

    private void catalog(String json) throws IOException {
        Files.writeString(root.resolve("catalog.json"), json);
    }

    @Test
    void listsMp4FilesAndIgnoresMacMetadataCopies() throws IOException {
        fake("clips/a/one.mp4", "x");
        fake("clips/a/._one.mp4", "meta");
        fake("clips/b/two.MP4", "yy");
        fake("clips/b/notes.txt", "n");

        List<Clip> clips = LibraryScanner.scan(root);

        assertEquals(List.of("a/one", "b/two"), clips.stream().map(Clip::key).toList());
        assertEquals(2, clips.get(1).size());
    }

    @Test
    void joinsCatalogMetadataAndFlagsUncatalogued() throws IOException {
        fake("clips/a/one.mp4", "x");
        fake("clips/a/two.mp4", "x");
        catalog("""
                {"version": 1, "clips": [
                  {"source": "a", "id": "c1", "file": "clips/a/one.mp4", "width": 1,
                   "status": {"ch": "approved"}, "uses": 3, "tags": ["t"]}
                ]}""");

        List<Clip> clips = LibraryScanner.scan(root);

        Clip one = clips.get(0);
        assertEquals("a/c1", one.key());
        assertTrue(one.catalogued());
        assertEquals(Map.of("ch", "approved"), one.status());
        assertEquals(3, one.uses());
        assertFalse(clips.get(1).catalogued());
        assertEquals("a/two", clips.get(1).key());
    }

    @Test
    void doesNotModifyCatalog() throws IOException {
        fake("clips/a/one.mp4", "x");
        catalog("{\"version\": 1, \"clips\": []}");
        Path file = root.resolve("catalog.json");
        var before = Files.getLastModifiedTime(file);
        String content = Files.readString(file);

        LibraryScanner.scan(root);

        assertEquals(content, Files.readString(file));
        assertEquals(before, Files.getLastModifiedTime(file));
    }

    @Test
    void missingCatalogOrClipsDirectoryGivesEmptyResult() throws IOException {
        assertTrue(LibraryScanner.scan(root).isEmpty());
    }

    @Test
    void rejectsUnknownCatalogVersion() throws IOException {
        catalog("{\"version\": 2, \"clips\": []}");

        assertThrows(IOException.class, () -> LibraryScanner.scan(root));
    }
}
