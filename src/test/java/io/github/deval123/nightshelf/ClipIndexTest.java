package io.github.deval123.nightshelf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClipIndexTest {

    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochSecond(1_000), ZoneOffset.UTC);
    private static final Description DESC =
            new Description("A street at night", List.of("car", "lamp"), "city", "night", "calm");

    @TempDir
    Path dir;

    private Path db() {
        return dir.resolve("sub").resolve("index.db");
    }

    private ClipIndex open() {
        return ClipIndex.open(db(), ClipIndex.MIGRATIONS, CLOCK);
    }

    private static Clip clip(String id, long size, long mtimeMillis) {
        return new Clip("src", id, Path.of(id + ".mp4"), size, mtimeMillis, true, Map.of(), 0);
    }

    private static StoredClip.Status status(ClipIndex index, String key) {
        return index.get(key).orElseThrow().status();
    }

    @Test
    void migratesFromEmptyAndIsIdempotent() throws Exception {
        try (ClipIndex index = open()) {
            assertEquals(ClipIndex.MIGRATIONS.size(), index.schemaVersion());
        }
        try (ClipIndex index = open()) {
            assertEquals(ClipIndex.MIGRATIONS.size(), index.schemaVersion());
            assertTrue(index.toProcess().isEmpty());
        }
    }

    @Test
    void appliesLaterMigrationsInOrder() {
        List<ClipIndex.Migration> v1 = ClipIndex.MIGRATIONS.subList(0, 1);
        List<ClipIndex.Migration> v2 = List.of(v1.get(0),
                ClipIndex.Migration.sql("ALTER TABLE clips ADD COLUMN extra TEXT"));
        try (ClipIndex index = ClipIndex.open(db(), v1, CLOCK)) {
            assertEquals(1, index.schemaVersion());
        }
        try (ClipIndex index = ClipIndex.open(db(), v2, CLOCK)) {
            assertEquals(2, index.schemaVersion());
        }
    }

    @Test
    void refusesANewerSchemaAndLeavesItUntouched() throws Exception {
        open().close();
        setVersion(99);

        IndexException e = assertThrows(IndexException.class, this::open);

        assertTrue(e.getMessage().contains("schema version 99"), e.getMessage());
        assertEquals(99, readVersion());
    }

    @Test
    void refusesAnIndexNewerThanTheMigrationsEvenAfterAnOlderRelease() throws Exception {
        List<ClipIndex.Migration> older = ClipIndex.MIGRATIONS.subList(0, 1);
        ClipIndex.open(db(), older, CLOCK).close();
        setVersion(ClipIndex.MIGRATIONS.size() + 1);

        assertThrows(IndexException.class, this::open);
        assertEquals(ClipIndex.MIGRATIONS.size() + 1, readVersion());
    }

    @Test
    void everyConnectionGetsWalForeignKeysAndBusyTimeout() throws Exception {
        open().close();

        try (Connection c = ClipIndex.connect(db())) {
            assertEquals("wal", pragma(c, "PRAGMA journal_mode"));
            assertEquals("1", pragma(c, "PRAGMA foreign_keys"));
            assertEquals("5000", pragma(c, "PRAGMA busy_timeout"));
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO clip_subjects (clip_key, position, subject) VALUES (?, ?, ?)")) {
                ps.setString(1, "no/such");
                ps.setInt(2, 0);
                ps.setString(3, "x");
                assertThrows(SQLException.class, ps::executeUpdate);
            }
        }
    }

    @Test
    void syncAddsPendingClips() {
        try (ClipIndex index = open()) {
            var changes = index.sync(List.of(clip("a", 10, 5_000), clip("b", 20, 6_000)));

            assertEquals(2, changes.added().size());
            assertEquals(List.of("src/a", "src/b"), index.toProcess());
            StoredClip a = index.get("src/a").orElseThrow();
            assertEquals(StoredClip.Status.PENDING, a.status());
            assertEquals(5, a.mtimeSeconds());
            assertEquals(10, a.size());
            assertFalse(a.removed());
            assertEquals(1_000_000, a.createdAt());
        }
    }

    @Test
    void interruptedRunResumesWithOnlyPendingAndFailedClips() {
        List<Clip> clips = List.of(clip("a", 1, 1_000), clip("b", 1, 1_000), clip("c", 1, 1_000), clip("d", 1, 1_000));
        try (ClipIndex index = open()) {
            index.sync(clips);
            index.recordDescribed("src/a", DESC);
            index.recordFailed("src/b", "model timed out");
            // the process dies here: c and d were never reached
        }

        try (ClipIndex index = open()) {
            var changes = index.sync(clips);

            assertEquals(4, changes.unchanged().size());
            assertEquals(List.of("src/b", "src/c", "src/d"), index.toProcess());
            assertEquals(DESC, index.get("src/a").orElseThrow().description());
            assertEquals("model timed out", index.get("src/b").orElseThrow().failureReason());
            index.recordDescribed("src/b", DESC);
            assertEquals(List.of("src/c", "src/d"), index.toProcess());
        }
    }

    @Test
    void incrementalRunTouchesOnlyWhatChanged() {
        try (ClipIndex index = open()) {
            index.sync(List.of(clip("keep", 1, 1_000), clip("grow", 1, 1_000), clip("gone", 1, 1_000)));
            for (String k : index.toProcess()) {
                index.recordDescribed(k, DESC);
            }
            assertTrue(index.toProcess().isEmpty());

            var changes = index.sync(List.of(clip("keep", 1, 1_400), clip("grow", 2, 1_000), clip("new", 3, 1_000)));

            assertEquals(List.of("src/new"), changes.added().stream().map(Clip::key).toList());
            assertEquals(List.of("src/grow"), changes.changed().stream().map(Clip::key).toList());
            assertEquals(List.of("src/keep"), changes.unchanged().stream().map(Clip::key).toList());
            assertEquals(List.of("src/gone"), changes.removed());

            assertEquals(StoredClip.Status.DESCRIBED, status(index, "src/keep"));
            StoredClip grow = index.get("src/grow").orElseThrow();
            assertEquals(StoredClip.Status.PENDING, grow.status());
            assertEquals(2, grow.size());
            assertEquals(null, grow.description());
            StoredClip gone = index.get("src/gone").orElseThrow();
            assertTrue(gone.removed());
            assertEquals(DESC, gone.description());
            assertEquals(List.of("src/grow", "src/new"), index.toProcess());
        }
    }

    @Test
    void removedClipThatReappearsIsUnflaggedAndKeepsItsResult() {
        try (ClipIndex index = open()) {
            index.sync(List.of(clip("a", 1, 1_000)));
            index.recordDescribed("src/a", DESC);
            index.sync(List.of());
            assertTrue(index.get("src/a").orElseThrow().removed());
            assertTrue(index.toProcess().isEmpty());

            index.sync(List.of(clip("a", 1, 1_000)));

            StoredClip a = index.get("src/a").orElseThrow();
            assertFalse(a.removed());
            assertEquals(StoredClip.Status.DESCRIBED, a.status());
            assertEquals(DESC, a.description());
        }
    }

    @Test
    void removedClipThatReappearsChangedIsPendingAgain() {
        try (ClipIndex index = open()) {
            index.sync(List.of(clip("a", 1, 1_000)));
            index.recordDescribed("src/a", DESC);
            index.sync(List.of());

            index.sync(List.of(clip("a", 5, 1_000)));

            StoredClip a = index.get("src/a").orElseThrow();
            assertFalse(a.removed());
            assertEquals(StoredClip.Status.PENDING, a.status());
            assertEquals(List.of("src/a"), index.toProcess());
        }
    }

    @Test
    void failureReasonIsSanitizedAndCapped() {
        try (ClipIndex index = open()) {
            index.sync(List.of(clip("a", 1, 1_000)));

            index.recordFailed("src/a", "bad \u001b[31m" + "x".repeat(1_000));

            String reason = index.get("src/a").orElseThrow().failureReason();
            assertFalse(reason.contains("\u001b"));
            assertEquals(ClipIndex.MAX_FAILURE_REASON, reason.length());
        }
    }

    @Test
    void redescribingReplacesSubjects() {
        try (ClipIndex index = open()) {
            index.sync(List.of(clip("a", 1, 1_000)));
            index.recordDescribed("src/a", DESC);
            Description other = new Description("Day", List.of("tree"), "park", "day", "happy");

            index.recordDescribed("src/a", other);

            assertEquals(other, index.get("src/a").orElseThrow().description());
        }
    }

    @Test
    void recordingAnUnknownClipFails() {
        try (ClipIndex index = open()) {
            assertThrows(IndexException.class, () -> index.recordDescribed("src/zzz", DESC));
            assertThrows(IndexException.class, () -> index.recordFailed("src/zzz", "x"));
        }
    }

    @Test
    void recognizesNetworkFilesystemTypes() {
        for (String t : List.of("smbfs", "nfs", "nfs4", "cifs", "smb3", "afpfs", "webdav", "fuse.sshfs", "9p")) {
            assertTrue(NetworkFilesystem.isNetworkType(t), t);
        }
        for (String t : List.of("apfs", "ext4", "xfs", "btrfs", "tmpfs", "zfs", "hfs", "overlay")) {
            assertFalse(NetworkFilesystem.isNetworkType(t), t);
        }
    }

    @Test
    void localTempDirectoryIsAccepted() {
        NetworkFilesystem.requireLocal(db());
    }

    private void setVersion(int v) throws SQLException {
        try (Connection c = ClipIndex.connect(db());
                PreparedStatement ps = c.prepareStatement("UPDATE schema_version SET version = ?")) {
            ps.setInt(1, v);
            ps.executeUpdate();
        }
    }

    private int readVersion() throws SQLException {
        try (Connection c = ClipIndex.connect(db());
                PreparedStatement ps = c.prepareStatement("SELECT MAX(version) FROM schema_version");
                ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static String pragma(Connection c, String sql) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getString(1);
        }
    }
}
