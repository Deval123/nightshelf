package io.github.deval123.nightshelf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClipIndexSearchTest {

    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochSecond(1_000), ZoneOffset.UTC);

    @TempDir
    Path dir;

    private ClipIndex open() {
        return ClipIndex.open(dir.resolve("index.db"), ClipIndex.MIGRATIONS, CLOCK);
    }

    private static Clip clip(String id, long size) {
        return new Clip("src", id, Path.of(id + ".mp4"), size, 1_000, true, Map.of(), 0);
    }

    private static Description desc(String text, String... subjects) {
        return new Description(text, List.of(subjects), "city", "night", "calm");
    }

    /** Clips seen by the library so far; sync() flags everything else as removed. */
    private final java.util.List<Clip> library = new java.util.ArrayList<>();

    private void described(ClipIndex index, String id, Description d) {
        library.add(clip(id, 1));
        index.sync(List.copyOf(library));
        index.recordDescribed("src/" + id, d);
    }

    @Test
    void migratesFromVersionOneToLatestAndBackfillsFullText() throws Exception {
        List<ClipIndex.Migration> v1 = ClipIndex.MIGRATIONS.subList(0, 1);
        try (ClipIndex index = ClipIndex.open(dir.resolve("index.db"), v1, CLOCK)) {
            assertEquals(1, index.schemaVersion());
        }
        // a version 1 database that already holds a described clip
        try (java.sql.Connection c = ClipIndex.connect(dir.resolve("index.db"))) {
            try (java.sql.PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO clips (key, source, id, size, mtime, status, description, setting, time_of_day,
                        mood, removed, created_at, updated_at)
                    VALUES (?, ?, ?, 1, 1, 'described', ?, 'city', 'night', 'calm', 0, 1, 1)""")) {
                ps.setString(1, "src/a");
                ps.setString(2, "src");
                ps.setString(3, "a");
                ps.setString(4, "a rainy street");
                ps.executeUpdate();
            }
            try (java.sql.PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO clip_subjects (clip_key, position, subject) VALUES (?, 0, ?)")) {
                ps.setString(1, "src/a");
                ps.setString(2, "umbrella");
                ps.executeUpdate();
            }
        }

        try (ClipIndex index = open()) {
            assertEquals(4, ClipIndex.MIGRATIONS.size());
            assertEquals(4, index.schemaVersion());
            assertEquals(List.of("src/a"), index.ftsSearch("umbrella", 10));
            assertEquals(List.of("src/a"), index.toEmbed("m"));
            index.recordEmbedding("src/a", "m", new float[] {1, 2});
            assertEquals(2, index.embeddingDimension("m"));
        }
    }

    @Test
    void fullTextFollowsEveryWrite() {
        try (ClipIndex index = open()) {
            index.sync(List.of(clip("a", 1)));
            assertTrue(index.ftsSearch("harbor", 10).isEmpty(), "pending clips are not searchable");

            index.recordDescribed("src/a", desc("a quiet harbor", "boat"));
            assertEquals(List.of("src/a"), index.ftsSearch("harbor", 10));
            assertEquals(List.of("src/a"), index.ftsSearch("boat", 10), "subjects are indexed");

            index.recordDescribed("src/a", desc("a busy market", "stall"));
            assertTrue(index.ftsSearch("harbor", 10).isEmpty());
            assertEquals(List.of("src/a"), index.ftsSearch("market", 10));

            index.sync(List.of());
            assertTrue(index.ftsSearch("market", 10).isEmpty(), "removed clips are excluded");

            index.sync(List.of(clip("a", 1)));
            assertEquals(List.of("src/a"), index.ftsSearch("market", 10), "reappeared clip is searchable again");

            index.sync(List.of(clip("a", 2)));
            assertTrue(index.ftsSearch("market", 10).isEmpty(), "a changed clip loses its text with its description");
        }
    }

    @Test
    void failedClipIsNotSearchable() {
        try (ClipIndex index = open()) {
            described(index, "a", desc("a quiet harbor"));
            index.recordFailed("src/a", "boom");

            assertTrue(index.ftsSearch("harbor", 10).isEmpty());
        }
    }

    @Test
    void hostileQueriesAreOnlyEverPlainText() {
        try (ClipIndex index = open()) {
            described(index, "a", desc("a lamp and a car", "lamp", "car"));
            described(index, "b", desc("an empty road"));

            // FTS operators and column filters are just words here
            assertEquals(List.of("src/a"), index.ftsSearch("lamp NOT car", 10));
            assertTrue(index.ftsSearch("ca*", 10).isEmpty(), "no prefix queries");
            assertTrue(index.ftsSearch("NEAR(road lamp, 1)", 10).containsAll(List.of("src/a", "src/b")));

            for (String hostile : List.of("\"", "\"\"\"", "a\" OR \"b", "(", ")", "*", "-", "^lamp", "key:lamp",
                    "body: lamp", "{key body}: lamp", "lamp AND", "OR", "NOT", "'; DROP TABLE clips; --",
                    "\u0000", "lamp\u001b[2J", "x".repeat(10_000), "lamp ".repeat(1_000))) {
                index.ftsSearch(hostile, 10);
            }
            assertTrue(index.get("src/a").isPresent(), "tables survived");
            assertTrue(index.ftsSearch("", 10).isEmpty());
            assertTrue(index.ftsSearch("!!! ???", 10).isEmpty());
        }
    }

    @Test
    void stopWordsAndFramingWordsAreDroppedFromTheQuery() {
        assertEquals("\"dog\" OR \"beach\"", FtsQuery.match("a video of the dog on the beach"));
        assertEquals("\"rainy\" OR \"street\"", FtsQuery.match("This clip shows a rainy street near it"));
        assertEquals("\"lamp\"", FtsQuery.match("The Scene Captures; Depicts, Features OF lamp"));
    }

    @Test
    void aQueryMadeOnlyOfIgnoredWordsFallsBackToTheOriginalWords() {
        assertEquals("\"in\" OR \"the\" OR \"video\"", FtsQuery.match("in the video"));
        assertEquals("\"and\"", FtsQuery.match("AND"));
        assertEquals(null, FtsQuery.match("... ***"));
    }

    @Test
    void ignoredWordsStayPlainTextWhenHostile() {
        assertEquals("\"lamp\" OR \"not\" OR \"car\"", FtsQuery.match("the lamp NOT car"));
        assertEquals("\"lamp\"", FtsQuery.match("\"the\" OR (lamp) -video"));
        try (ClipIndex index = open()) {
            described(index, "a", desc("a lamp in the video", "lamp"));
            assertEquals(List.of("src/a"), index.ftsSearch("the \" OR * video lamp", 10));
            assertEquals(List.of("src/a"), index.ftsSearch("in the", 10), "falls back to the original words");
        }
    }

    @Test
    void queryBuilderQuotesEveryWord() {
        assertEquals("\"rainy\" OR \"city\" OR \"night\"", FtsQuery.match("Rainy city, AND night!"));
        assertEquals(null, FtsQuery.match("*** \"\""));
        assertEquals(FtsQuery.MAX_TOKENS, FtsQuery.match("w ".repeat(100)).split(" OR ").length);
    }

    @Test
    void vectorsAreStaleWhenTheEmbedModelChanges() {
        try (ClipIndex index = open()) {
            described(index, "a", desc("x"));
            index.recordEmbedding("src/a", "model-1", new float[] {1, 0, 0});
            assertTrue(index.toEmbed("model-1").isEmpty());
            assertEquals(1, index.currentEmbeddings("model-1").size());

            assertEquals(List.of("src/a"), index.toEmbed("model-2"));
            assertTrue(index.currentEmbeddings("model-2").isEmpty());

            index.recordEmbedding("src/a", "model-2", new float[] {0, 1});
            assertTrue(index.toEmbed("model-2").isEmpty());
            assertEquals(List.of("src/a"), index.toEmbed("model-1"), "the old vector was replaced");
            assertTrue(index.currentEmbeddings("model-1").isEmpty());
        }
    }

    @Test
    void aClipLosesItsVectorWithItsDescription() {
        try (ClipIndex index = open()) {
            described(index, "a", desc("x"));
            described(index, "b", desc("y"));
            index.recordEmbedding("src/a", "m", new float[] {1, 0});
            index.recordEmbedding("src/b", "m", new float[] {1, 0});

            index.recordDescribed("src/a", desc("redescribed"));
            index.sync(List.of(clip("a", 1), clip("b", 99)));

            assertTrue(index.currentEmbeddings("m").isEmpty());
        }
    }

    @Test
    void removedClipsAreNotEmbeddedOrSearchedByVector() {
        try (ClipIndex index = open()) {
            described(index, "a", desc("x"));
            index.recordEmbedding("src/a", "m", new float[] {1, 0});

            index.sync(List.of());

            assertTrue(index.toEmbed("m").isEmpty());
            assertTrue(index.currentEmbeddings("m").isEmpty());
        }
    }

    @Test
    void vectorStorageIsStrict() {
        try (ClipIndex index = open()) {
            described(index, "a", desc("x"));
            described(index, "b", desc("y"));
            index.recordEmbedding("src/a", "m", new float[] {1, 2, 3});

            assertThrows(IndexException.class, () -> index.recordEmbedding("src/b", "m", new float[] {1, 2}));
            assertThrows(IndexException.class, () -> index.recordEmbedding("src/b", "m", new float[] {Float.NaN}));
            assertThrows(IndexException.class, () -> index.recordEmbedding("src/b", "m", new float[0]));
            assertThrows(IndexException.class, () -> index.recordEmbedding("src/zzz", "m", new float[] {1, 2, 3}));
            assertFalse(index.currentEmbeddings("m").containsKey("src/b"));
            assertEquals(List.of(1f, 2f, 3f), List.of(index.currentEmbeddings("m").get("src/a")[0],
                    index.currentEmbeddings("m").get("src/a")[1], index.currentEmbeddings("m").get("src/a")[2]));
        }
    }
}
