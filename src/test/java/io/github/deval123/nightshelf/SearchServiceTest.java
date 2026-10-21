package io.github.deval123.nightshelf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SearchServiceTest {

    private static final String MODEL = "m";

    @TempDir
    Path root;

    private Catalog catalog = Catalog.empty();
    private Map<String, Clip> clips;

    private ClipIndex index(String... idsAndTexts) throws IOException {
        for (int i = 0; i < idsAndTexts.length; i += 2) {
            Path f = root.resolve("clips/src/" + idsAndTexts[i] + ".mp4");
            Files.createDirectories(f.getParent());
            Files.writeString(f, "x");
        }
        catalog = LibraryScanner.loadCatalog(root);
        List<Clip> scanned = LibraryScanner.scan(root, catalog);
        clips = scanned.stream().collect(Collectors.toMap(Clip::key, Function.identity()));
        ClipIndex index = ClipIndex.open(root.resolve("db/index.db"));
        index.sync(scanned);
        for (int i = 0; i < idsAndTexts.length; i += 2) {
            index.recordDescribed("src/" + idsAndTexts[i],
                    new Description(idsAndTexts[i + 1], List.of(), "place", "day", "calm"));
        }
        return index;
    }

    private SearchService.Result search(ClipIndex index, Embedder embedder, String query, SearchFilter filter) {
        return SearchService.search(index, embedder, MODEL, query, filter, 10, root, clips, catalog);
    }

    private static List<String> keys(SearchService.Result r) {
        return r.hits().stream().map(SearchService.Hit::key).toList();
    }

    @Test
    void fusesBothRankingsWithReciprocalRanks() throws Exception {
        try (ClipIndex index = index("x", "needle here", "y", "other words", "z", "more words", "w", "bare")) {
            index.recordEmbedding("src/y", MODEL, new float[] {1, 0});
            index.recordEmbedding("src/x", MODEL, new float[] {0.8f, 0.6f});
            index.recordEmbedding("src/z", MODEL, new float[] {0, 1});

            SearchService.Result r = search(index, t -> new float[] {1, 0}, "needle", SearchFilter.none());

            // text: x. semantic: y, x, z. x is in both lists, so it wins; y and z follow by their semantic rank.
            assertEquals(List.of("src/x", "src/y", "src/z"), keys(r));
            assertEquals(1.0 / 61 + 1.0 / 62, r.hits().get(0).score(), 1e-12);
            assertEquals(1.0 / 61, r.hits().get(1).score(), 1e-12);
            assertEquals(1.0 / 63, r.hits().get(2).score(), 1e-12);
            assertTrue(r.notes().isEmpty(), r.notes().toString());
        }
    }

    @Test
    void equalScoresAreBrokenByKey() throws Exception {
        try (ClipIndex index = index("b", "needle", "a", "other")) {
            index.recordEmbedding("src/a", MODEL, new float[] {1, 0});

            SearchService.Result r = search(index, t -> new float[] {1, 0}, "needle", SearchFilter.none());

            // b is first in the text list, a first in the semantic list: both score 1/61
            assertEquals(List.of("src/a", "src/b"), keys(r));
        }
    }

    @Test
    void fallsBackToFullTextWhenEmbeddingFails() throws Exception {
        try (ClipIndex index = index("x", "needle here", "y", "other words")) {
            index.recordEmbedding("src/y", MODEL, new float[] {1, 0});
            Embedder broken = t -> {
                throw new EmbedException("connection refused", true, null);
            };

            SearchService.Result r = search(index, broken, "needle", SearchFilter.none());

            assertEquals(List.of("src/x"), keys(r));
            assertEquals(1, r.notes().size());
            assertTrue(r.notes().get(0).contains("full-text only"), r.notes().get(0));
            assertTrue(r.notes().get(0).contains("connection refused"), r.notes().get(0));
        }
    }

    @Test
    void worksWithoutAnEmbedderAndWithoutVectors() throws Exception {
        try (ClipIndex index = index("x", "needle here")) {
            SearchService.Result none = search(index, null, "needle", SearchFilter.none());
            SearchService.Result noVectors = search(index, t -> new float[] {1}, "needle", SearchFilter.none());

            assertEquals(List.of("src/x"), keys(none));
            assertTrue(none.notes().get(0).contains("OLLAMA_URL"), none.notes().toString());
            assertEquals(List.of("src/x"), keys(noVectors));
            assertTrue(noVectors.notes().get(0).contains("run embed"), noVectors.notes().toString());
        }
    }

    @Test
    void vectorsOfAnotherModelAreIgnored() throws Exception {
        try (ClipIndex index = index("x", "needle here", "y", "other words")) {
            index.recordEmbedding("src/y", "old-model", new float[] {1, 0});

            SearchService.Result r = search(index, t -> new float[] {1, 0}, "needle", SearchFilter.none());

            assertEquals(List.of("src/x"), keys(r));
        }
    }

    @Test
    void limitsTheNumberOfResults() throws Exception {
        try (ClipIndex index = index("a", "needle", "b", "needle", "c", "needle")) {
            SearchService.Result r = SearchService.search(index, null, MODEL, "needle", SearchFilter.none(), 2,
                    root, clips, catalog);

            assertEquals(2, r.hits().size());
        }
    }

    @Test
    void rejectsAQueryWithoutWords() throws Exception {
        try (ClipIndex index = index("a", "needle")) {
            assertThrows(IllegalArgumentException.class, () -> search(index, null, "*** \"", SearchFilter.none()));
        }
    }

    @Test
    void leavesOutHitsWhosePathIsOutsideTheLibrary() throws Exception {
        Path outside = Files.createTempFile("outside", ".mp4");
        try (ClipIndex index = index("a", "needle", "b", "needle")) {
            Clip a = clips.get("src/a");
            clips.put("src/a", new Clip(a.source(), a.id(), outside, a.size(), a.mtimeMillis(), true, Map.of(), 0));

            SearchService.Result r = search(index, null, "needle", SearchFilter.none());

            assertEquals(List.of("src/b"), keys(r));
            assertTrue(r.notes().stream().anyMatch(n -> n.contains("outside the clip library")), r.notes().toString());
            assertTrue(r.hits().get(0).path().startsWith(root.toRealPath()));
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void filtersByChannelStatusFromTheCatalog() throws Exception {
        Files.writeString(root.resolve("catalog.json"), """
                {"version": 1, "clips": {
                  "src:ok": {"source": "src", "id": "ok", "file": "clips/src/ok.mp4", "width": 1080, "height": 1920,
                    "tags": [], "addedAt": "2025-01-01T00:00:00Z", "status": {"chan1": "approved"},
                    "uses": [], "size": 1, "duration": 12.5, "orientation": "portrait"},
                  "src:bad": {"source": "src", "id": "bad", "file": "clips/src/bad.mp4", "width": 1080, "height": 1920,
                    "tags": [], "addedAt": "2025-01-01T00:00:00Z", "status": {"chan1": "rejected", "chan2": "approved"},
                    "uses": [], "size": 1, "duration": 3.0, "orientation": "portrait"},
                  "src:used": {"source": "src", "id": "used", "file": "clips/src/used.mp4", "width": 1920, "height": 1080,
                    "tags": [], "addedAt": "2025-01-01T00:00:00Z", "status": {},
                    "uses": [{"channel": "chan1", "video": "v", "scene": 1}],
                    "size": 1, "duration": 20.0, "orientation": "landscape"}
                }}""");
        try (ClipIndex index = index("ok", "needle", "bad", "needle", "used", "needle", "new", "needle")) {
            assertEquals(List.of("src/bad", "src/new", "src/ok", "src/used"),
                    sorted(search(index, null, "needle", SearchFilter.none())));
            assertEquals(List.of("src/new", "src/ok", "src/used"),
                    sorted(search(index, null, "needle", new SearchFilter("chan1", null, null, null))));
            assertEquals(List.of("src/ok"),
                    sorted(search(index, null, "needle", new SearchFilter("chan1", SearchFilter.Status.APPROVED, null, null))));
            assertEquals(List.of("src/bad"),
                    sorted(search(index, null, "needle", new SearchFilter("chan1", SearchFilter.Status.REJECTED, null, null))));
            // unused: never used on chan1 and not rejected for it (src/bad is rejected for chan1)
            assertEquals(List.of("src/new", "src/ok"),
                    sorted(search(index, null, "needle", new SearchFilter("chan1", SearchFilter.Status.UNUSED, null, null))));
            assertEquals(List.of("src/bad", "src/new", "src/ok", "src/used"),
                    sorted(search(index, null, "needle", new SearchFilter("chan2", SearchFilter.Status.UNUSED, null, null))));
            assertEquals(4,
                    search(index, null, "needle", new SearchFilter("chan1", SearchFilter.Status.ANY, null, null)).hits().size());
            assertEquals(List.of("src/ok", "src/used"),
                    sorted(search(index, null, "needle", new SearchFilter(null, null, null, 10.0))));
            assertEquals(List.of("src/used"),
                    sorted(search(index, null, "needle", new SearchFilter(null, null, "Landscape", null))));

            SearchService.Hit bad = search(index, null, "needle", SearchFilter.none()).hits().stream()
                    .filter(h -> h.key().equals("src/bad")).findFirst().orElseThrow();
            assertEquals(Map.of("chan1", "rejected", "chan2", "approved"), bad.channelStatus());
            assertEquals(3.0, bad.duration());
            SearchService.Hit used = search(index, null, "needle", SearchFilter.none()).hits().stream()
                    .filter(h -> h.key().equals("src/used")).findFirst().orElseThrow();
            assertEquals(List.of("chan1"), used.usedOn());
        }
    }

    @Test
    void descriptionsAreShownWithoutTheirFramingButStayAsStored() throws Exception {
        try (ClipIndex index = index("x", "The video shows a lamp at night")) {
            SearchService.Result r = search(index, null, "lamp", SearchFilter.none());

            assertEquals("A lamp at night", r.hits().get(0).description());
            assertEquals("The video shows a lamp at night", index.get("src/x").orElseThrow().description().description());
        }
    }

    private static List<String> sorted(SearchService.Result r) {
        return r.hits().stream().map(SearchService.Hit::key).sorted().toList();
    }

    @Test
    void hitsCarryTheirCosineSimilarityOrNoneWhenTheyCameFromFullTextOnly() throws Exception {
        try (ClipIndex index = index("x", "needle here", "y", "other words", "t", "needle too")) {
            index.recordEmbedding("src/y", MODEL, new float[] {1, 0});
            index.recordEmbedding("src/x", MODEL, new float[] {0.8f, 0.6f});

            SearchService.Result r = search(index, t -> new float[] {1, 0}, "needle", SearchFilter.none());

            Map<String, Double> sims = new java.util.HashMap<>();
            r.hits().forEach(h -> sims.put(h.key(), h.similarity()));
            assertEquals(1.0, sims.get("src/y"), 1e-6);
            assertEquals(0.8, sims.get("src/x"), 1e-6);
            assertTrue(sims.containsKey("src/t") && sims.get("src/t") == null, "no vector, so full-text only");
        }
    }

    @Test
    void minSimilarityDropsLowAndFullTextOnlyHits() throws Exception {
        try (ClipIndex index = index("x", "needle here", "y", "other words", "z", "more words", "t", "needle too")) {
            index.recordEmbedding("src/y", MODEL, new float[] {1, 0});
            index.recordEmbedding("src/x", MODEL, new float[] {0.8f, 0.6f});
            index.recordEmbedding("src/z", MODEL, new float[] {0, 1});
            Embedder q = text -> new float[] {1, 0};

            SearchService.Result all = SearchService.search(index, q, MODEL, "needle", SearchFilter.none(), 10,
                    root, clips, catalog, null);
            SearchService.Result mid = SearchService.search(index, q, MODEL, "needle", SearchFilter.none(), 10,
                    root, clips, catalog, 0.5);
            SearchService.Result high = SearchService.search(index, q, MODEL, "needle", SearchFilter.none(), 10,
                    root, clips, catalog, 0.9);

            assertEquals(List.of("src/t", "src/x", "src/y", "src/z"), sorted(all));
            assertEquals(List.of("src/x", "src/y"), sorted(mid));
            assertTrue(mid.notes().stream().anyMatch(n -> n.contains("2 result(s) dropped by --min-similarity")),
                    mid.notes().toString());
            assertEquals(List.of("src/y"), sorted(high));
        }
    }

    @Test
    void minSimilarityKeepsAHitAtExactlyTheThreshold() throws Exception {
        try (ClipIndex index = index("x", "needle here")) {
            index.recordEmbedding("src/x", MODEL, new float[] {1, 0});

            SearchService.Result r = SearchService.search(index, t -> new float[] {1, 0}, MODEL, "needle",
                    SearchFilter.none(), 10, root, clips, catalog, 1.0);

            assertEquals(List.of("src/x"), sorted(r));
        }
    }

    @Test
    void minSimilarityWithoutSemanticSearchDropsEverythingAndSaysSo() throws Exception {
        try (ClipIndex index = index("x", "needle here")) {
            SearchService.Result r = SearchService.search(index, null, MODEL, "needle", SearchFilter.none(), 10,
                    root, clips, catalog, 0.1);

            assertTrue(r.hits().isEmpty());
            assertTrue(r.notes().stream().anyMatch(n -> n.contains("dropped by --min-similarity")), r.notes().toString());
        }
    }

    @Test
    void minSimilarityIsAppliedBeforeTheLimit() throws Exception {
        try (ClipIndex index = index("a", "needle", "b", "needle", "c", "needle")) {
            index.recordEmbedding("src/c", MODEL, new float[] {1, 0});

            SearchService.Result r = SearchService.search(index, t -> new float[] {1, 0}, MODEL, "needle",
                    SearchFilter.none(), 1, root, clips, catalog, 0.9);

            assertEquals(List.of("src/c"), sorted(r));
        }
    }
}
