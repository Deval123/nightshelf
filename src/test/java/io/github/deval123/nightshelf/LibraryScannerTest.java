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
                {"version": 1, "clips": {
                  "a:c1": {"source": "a", "id": "c1", "file": "clips/a/one.mp4", "width": 1,
                   "status": {"ch": "approved"}, "uses": [{"channel": "ch", "video": "v", "scene": 1}, {"channel": "ch", "video": "w", "scene": 2}, {"channel": "ch", "video": "x"}], "tags": ["t"]}
                }}""");

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
        catalog("{\"version\": 1, \"clips\": {}}");
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
        catalog("{\"version\": 2, \"clips\": {}}");

        assertThrows(IOException.class, () -> LibraryScanner.scan(root));
    }

    /** Invented data in the shape of the real catalog.json. */
    private static final String REAL_SHAPE_CATALOG = """
            {"version": 1, "clips": {
              "pixabay:111": {"source": "pixabay", "id": "111", "file": "clips/pixabay/111.mp4",
                "width": 1080, "height": 1920, "tags": ["night", "city"],
                "addedAt": "2025-01-01T00:00:00Z",
                "status": {"chan1": "approved", "chan2": "rejected"},
                "uses": [], "size": 1, "duration": 4.5, "orientation": "portrait"},
              "pixabay:222": {"source": "pixabay", "id": "222", "file": "clips/pixabay/222.mp4",
                "width": 1920, "height": 1080, "tags": [],
                "addedAt": "2025-01-02T00:00:00Z",
                "status": {"chan1": "approved"},
                "uses": [{"channel": "chan1", "video": "v1", "scene": 1},
                         {"channel": "chan2", "video": "v2", "scene": 3}],
                "size": 1, "duration": 8.0, "orientation": "landscape"},
              "pexels:333": {"source": "pexels", "id": "333", "file": "clips/pexels/333.mp4",
                "width": 1080, "height": 1920, "tags": ["sea"],
                "addedAt": "2025-01-03T00:00:00Z",
                "status": {},
                "uses": [{"channel": "chan2", "video": "v3", "scene": 2}],
                "size": 1, "duration": 6.0, "orientation": "portrait"}
            }}""";

    @Test
    void readsRealShapeCatalogEndToEnd() throws IOException {
        fake("clips/pixabay/111.mp4", "x");
        fake("clips/pixabay/._111.mp4", "meta");
        fake("clips/pixabay/222.mp4", "x");
        fake("clips/pixabay/444.mp4", "x");
        fake("clips/pexels/333.mp4", "x");
        catalog(REAL_SHAPE_CATALOG);

        List<Clip> clips = LibraryScanner.scan(root);

        assertEquals(List.of("pexels/333", "pixabay/111", "pixabay/222", "pixabay/444"),
                clips.stream().map(Clip::key).toList());
        assertEquals(List.of(1, 0, 2, 0), clips.stream().map(Clip::uses).toList());
        assertEquals(Map.of("chan1", "approved", "chan2", "rejected"), clips.get(1).status());
        assertEquals(List.of(true, true, true, false), clips.stream().map(Clip::catalogued).toList());
    }

    @Test
    void rejectsClipsThatIsNotAnObjectWithClearMessage() throws IOException {
        catalog("{\"version\": 1, \"clips\": []}");

        IOException e = assertThrows(IOException.class, () -> LibraryScanner.scan(root));

        assertTrue(e.getMessage().contains("\"clips\" must be an object keyed by"), e.getMessage());
    }

    @Test
    void doesNotFollowSymlinks() throws IOException {
        Path outside = Files.createTempDirectory("outside");
        try {
            Files.writeString(outside.resolve("secret.mp4"), "x");
            Files.createDirectories(outside.resolve("linked"));
            Files.writeString(outside.resolve("linked/deep.mp4"), "x");
            fake("clips/a/real.mp4", "x");
            Files.createSymbolicLink(root.resolve("clips/a/link.mp4"), outside.resolve("secret.mp4"));
            Files.createSymbolicLink(root.resolve("clips/b"), outside.resolve("linked"));

            List<Clip> clips = LibraryScanner.scan(root);

            assertEquals(List.of("a/real"), clips.stream().map(Clip::key).toList());
        } finally {
            Files.deleteIfExists(outside.resolve("linked/deep.mp4"));
            Files.deleteIfExists(outside.resolve("linked"));
            Files.deleteIfExists(outside.resolve("secret.mp4"));
            Files.deleteIfExists(outside);
        }
    }
}
