package io.github.deval123.nightshelf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClipIndexRunsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochSecond(2_000), ZoneOffset.UTC);

    @TempDir
    Path dir;

    private static Clip clip(String id) {
        return new Clip("src", id, Path.of(id + ".mp4"), 1, 1_000, true, Map.of(), 0);
    }

    @Test
    void migratesFromVersionThreeKeepingExistingRows() throws Exception {
        Path db = dir.resolve("index.db");
        try (ClipIndex index = ClipIndex.open(db, ClipIndex.MIGRATIONS.subList(0, 3), CLOCK)) {
            assertEquals(3, index.schemaVersion());
        }
        // a version 3 database that already holds a failed clip (written without the attempts column)
        try (Connection c = ClipIndex.connect(db);
                PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO clips (key, source, id, size, mtime, status, failure_reason, removed,
                            created_at, updated_at)
                        VALUES (?, ?, ?, 1, 1, 'failed', 'old failure', 0, 1, 1)""")) {
            ps.setString(1, "src/a");
            ps.setString(2, "src");
            ps.setString(3, "a");
            ps.executeUpdate();
        }

        try (ClipIndex index = ClipIndex.open(db, ClipIndex.MIGRATIONS, CLOCK)) {
            assertEquals(4, index.schemaVersion());
            assertEquals(StoredClip.Status.FAILED, index.get("src/a").orElseThrow().status());
            assertEquals(List.of("src/a"), index.toProcess(), "attempts start at zero");
            assertTrue(index.lastRun().isEmpty());
            long id = index.startRun("v", "e");
            index.finishRun(id, 1, 2, 3, 4, 5.5, 1.5, "completed");
            assertEquals(new RunRecord(id, 2_000_000, 2_000_000, 1, 2, 3, 4, 5.5, 1.5, "v", "e", "completed"),
                    index.lastRun().orElseThrow());
        }
        try (Connection c = ClipIndex.connect(db);
                PreparedStatement ps = c.prepareStatement("SELECT attempts FROM clips WHERE key = ?")) {
            ps.setString(1, "src/a");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(0, rs.getInt(1));
            }
        }
    }

    @Test
    void anUnfinishedRunIsVisible() {
        try (ClipIndex index = ClipIndex.open(dir.resolve("index.db"), ClipIndex.MIGRATIONS, CLOCK)) {
            index.startRun("v", "e");

            assertFalse(index.lastRun().orElseThrow().finished());
        }
    }

    @Test
    void attemptsCountSeparateFailuresAndResetOnSuccessOrChange() {
        try (ClipIndex index = ClipIndex.open(dir.resolve("index.db"), ClipIndex.MIGRATIONS, CLOCK)) {
            index.sync(List.of(clip("a"), clip("b")));
            for (int i = 0; i < 3; i++) {
                index.recordFailed("src/a", "x");
                index.recordFailed("src/b", "x");
            }
            assertTrue(index.toProcess().isEmpty());
            assertEquals(List.of("src/a", "src/b"), index.toProcess(true));
            assertEquals(2, index.totals("e").givenUp());

            index.recordDescribed("src/a", new Description("d", List.of(), "s", "day", "m"));
            index.sync(List.of(clip("a"), new Clip("src", "b", Path.of("b.mp4"), 99, 1_000, true, Map.of(), 0)));

            assertEquals(List.of("src/b"), index.toProcess(), "a changed clip starts over");
            assertEquals(0, index.totals("e").givenUp());
        }
    }

    @Test
    void totalsCountEachGroup() {
        try (ClipIndex index = ClipIndex.open(dir.resolve("index.db"), ClipIndex.MIGRATIONS, CLOCK)) {
            index.sync(List.of(clip("a"), clip("b"), clip("c"), clip("d")));
            index.recordDescribed("src/a", new Description("d", List.of(), "s", "day", "m"));
            index.recordEmbedding("src/a", "e", new float[] {1});
            index.recordFailed("src/b", "x");
            index.sync(List.of(clip("a"), clip("b"), clip("c")));

            assertEquals(new IndexTotals(3, 1, 1, 1, 0, 1, 1), index.totals("e"));
            assertEquals(0, index.totals("other").embedded());
        }
    }
}
