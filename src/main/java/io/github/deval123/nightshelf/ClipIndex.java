package io.github.deval123.nightshelf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.sqlite.SQLiteConfig;

/**
 * The SQLite index: one row per clip, written in small transactions so that a
 * run interrupted at any point resumes with only the unfinished clips.
 * All statements are parameterized; SQL text is constant.
 */
public final class ClipIndex implements AutoCloseable {

    static final int MAX_FAILURE_REASON = 300;
    private static final int BUSY_TIMEOUT_MS = 5000;

    /** Ordered migrations; entry i takes the schema from version i to i+1. */
    static final List<List<String>> MIGRATIONS = List.of(List.of(
            """
            CREATE TABLE clips (
                key            TEXT PRIMARY KEY,
                source         TEXT NOT NULL,
                id             TEXT NOT NULL,
                size           INTEGER NOT NULL,
                mtime          INTEGER NOT NULL,
                status         TEXT NOT NULL CHECK (status IN ('pending', 'described', 'failed')),
                description    TEXT,
                setting        TEXT,
                time_of_day    TEXT,
                mood           TEXT,
                failure_reason TEXT,
                removed        INTEGER NOT NULL DEFAULT 0 CHECK (removed IN (0, 1)),
                created_at     INTEGER NOT NULL,
                updated_at     INTEGER NOT NULL
            )""",
            """
            CREATE TABLE clip_subjects (
                clip_key TEXT NOT NULL REFERENCES clips (key) ON DELETE CASCADE,
                position INTEGER NOT NULL,
                subject  TEXT NOT NULL,
                PRIMARY KEY (clip_key, position)
            )""",
            "CREATE INDEX clips_status ON clips (status, removed)"));

    private interface Work<T> {
        T run(Connection c) throws SQLException;
    }

    private final Connection connection;
    private final Clock clock;

    private ClipIndex(Connection connection, Clock clock) {
        this.connection = connection;
        this.clock = clock;
    }

    public static ClipIndex open(Path dbFile) {
        return open(dbFile, MIGRATIONS, Clock.systemUTC());
    }

    static ClipIndex open(Path dbFile, List<List<String>> migrations, Clock clock) {
        NetworkFilesystem.requireLocal(dbFile);
        try {
            Files.createDirectories(dbFile.toAbsolutePath().getParent());
        } catch (IOException e) {
            throw new IndexException("Cannot create the directory for " + dbFile + ": " + e.getMessage(), e);
        }
        Connection connection = connect(dbFile);
        try {
            migrate(connection, migrations);
        } catch (RuntimeException | SQLException e) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // the original failure is the one to report
            }
            if (e instanceof IndexException ie) {
                throw ie;
            }
            throw new IndexException("Cannot prepare the index " + dbFile + ": " + e.getMessage(), e);
        }
        return new ClipIndex(connection, clock);
    }

    /** Every connection gets WAL, foreign keys and a busy timeout. */
    static Connection connect(Path dbFile) {
        SQLiteConfig config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.enforceForeignKeys(true);
        config.setBusyTimeout(BUSY_TIMEOUT_MS);
        try {
            return java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath(),
                    config.toProperties());
        } catch (SQLException e) {
            throw new IndexException("Cannot open the index " + dbFile + ": " + e.getMessage(), e);
        }
    }

    private static void migrate(Connection c, List<List<String>> migrations) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)");
        }
        int current = schemaVersion(c);
        if (current > migrations.size()) {
            throw new IndexException("The index has schema version " + current
                    + " but this nightshelf only knows up to " + migrations.size()
                    + ". Upgrade nightshelf; the index was left untouched.");
        }
        for (int v = current; v < migrations.size(); v++) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                for (String sql : migrations.get(v)) {
                    s.execute(sql);
                }
                try (PreparedStatement del = c.prepareStatement("DELETE FROM schema_version")) {
                    del.executeUpdate();
                }
                try (PreparedStatement ins = c.prepareStatement("INSERT INTO schema_version (version) VALUES (?)")) {
                    ins.setInt(1, v + 1);
                    ins.executeUpdate();
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    private static int schemaVersion(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT MAX(version) FROM schema_version");
                ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    int schemaVersion() {
        return inTransaction(ClipIndex::schemaVersion);
    }

    /**
     * Brings the index in line with a fresh scan, in one transaction: added
     * clips become pending, changed clips go back to pending, unchanged clips
     * are left alone and vanished clips are flagged removed (never deleted).
     * A removed clip that reappears is un-flagged; its result is kept if its
     * size and mtime are the same, otherwise it is pending again.
     */
    public ChangeDetector.Changes sync(List<Clip> scanned) {
        return inTransaction(c -> {
            ChangeDetector.Changes changes = ChangeDetector.detect(known(c), scanned);
            long now = clock.millis();
            for (Clip clip : changes.added()) {
                Optional<long[]> existing = sizeAndMtime(c, clip.key());
                if (existing.isEmpty()) {
                    insertPending(c, clip, now);
                } else if (existing.get()[0] == clip.size() && existing.get()[1] == seconds(clip)) {
                    setRemoved(c, clip.key(), false, now);
                } else {
                    resetToPending(c, clip, now);
                }
            }
            for (Clip clip : changes.changed()) {
                resetToPending(c, clip, now);
            }
            for (String key : changes.removed()) {
                setRemoved(c, key, true, now);
            }
            return changes;
        });
    }

    /** Keys of the clips that still need work (pending or failed), in a stable order. */
    public List<String> toProcess() {
        return inTransaction(c -> {
            List<String> keys = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT key FROM clips WHERE removed = 0 AND status IN (?, ?) ORDER BY key")) {
                ps.setString(1, StoredClip.Status.PENDING.db());
                ps.setString(2, StoredClip.Status.FAILED.db());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        keys.add(rs.getString(1));
                    }
                }
            }
            return keys;
        });
    }

    /** Stores the result for one clip in its own transaction. */
    public void recordDescribed(String key, Description d) {
        inTransaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                UPDATE clips SET status = ?, description = ?, setting = ?, time_of_day = ?, mood = ?,
                failure_reason = NULL, updated_at = ? WHERE key = ?
                """)) {
                ps.setString(1, StoredClip.Status.DESCRIBED.db());
                ps.setString(2, d.description());
                ps.setString(3, d.setting());
                ps.setString(4, d.timeOfDay());
                ps.setString(5, d.mood());
                ps.setLong(6, clock.millis());
                ps.setString(7, key);
                requireUpdated(ps.executeUpdate(), key);
            }
            deleteSubjects(c, key);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO clip_subjects (clip_key, position, subject) VALUES (?, ?, ?)")) {
                for (int i = 0; i < d.subjects().size(); i++) {
                    ps.setString(1, key);
                    ps.setInt(2, i);
                    ps.setString(3, d.subjects().get(i));
                    ps.executeUpdate();
                }
            }
            return null;
        });
    }

    /** Records a per-clip failure; the reason is sanitized and capped. */
    public void recordFailed(String key, String reason) {
        inTransaction(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE clips SET status = ?, failure_reason = ?, updated_at = ? WHERE key = ?")) {
                ps.setString(1, StoredClip.Status.FAILED.db());
                ps.setString(2, Sanitize.clean(reason, MAX_FAILURE_REASON));
                ps.setLong(3, clock.millis());
                ps.setString(4, key);
                requireUpdated(ps.executeUpdate(), key);
            }
            return null;
        });
    }

    public Optional<StoredClip> get(String key) {
        return inTransaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                SELECT key, source, id, size, mtime, status, description, setting, time_of_day, mood,
                failure_reason, removed, created_at, updated_at FROM clips WHERE key = ?
                """)) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    String description = rs.getString("description");
                    Description d = description == null ? null
                            : new Description(description, subjects(c, key), rs.getString("setting"),
                                    rs.getString("time_of_day"), rs.getString("mood"));
                    return Optional.of(new StoredClip(rs.getString("key"), rs.getString("source"),
                            rs.getString("id"), rs.getLong("size"), rs.getLong("mtime"),
                            StoredClip.Status.fromDb(rs.getString("status")), d,
                            rs.getString("failure_reason"), rs.getInt("removed") == 1,
                            rs.getLong("created_at"), rs.getLong("updated_at")));
                }
            }
        });
    }

    private static List<String> subjects(Connection c, String key) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT subject FROM clip_subjects WHERE clip_key = ? ORDER BY position")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        }
        return out;
    }

    /** Clips not flagged removed, as the change detector sees them. */
    private static Map<String, ChangeDetector.Known> known(Connection c) throws SQLException {
        Map<String, ChangeDetector.Known> known = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT key, size, mtime FROM clips WHERE removed = 0");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                known.put(rs.getString(1), new ChangeDetector.Known(rs.getLong(2), rs.getLong(3) * 1000));
            }
        }
        return known;
    }

    private static Optional<long[]> sizeAndMtime(Connection c, String key) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT size, mtime FROM clips WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(new long[] {rs.getLong(1), rs.getLong(2)}) : Optional.empty();
            }
        }
    }

    private static void insertPending(Connection c, Clip clip, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO clips (key, source, id, size, mtime, status, removed, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?)
                """)) {
            ps.setString(1, clip.key());
            ps.setString(2, clip.source());
            ps.setString(3, clip.id());
            ps.setLong(4, clip.size());
            ps.setLong(5, seconds(clip));
            ps.setString(6, StoredClip.Status.PENDING.db());
            ps.setLong(7, now);
            ps.setLong(8, now);
            ps.executeUpdate();
        }
    }

    private static void resetToPending(Connection c, Clip clip, long now) throws SQLException {
        deleteSubjects(c, clip.key());
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE clips SET size = ?, mtime = ?, status = ?, description = NULL, setting = NULL,
                time_of_day = NULL, mood = NULL, failure_reason = NULL, removed = 0, updated_at = ?
                WHERE key = ?
                """)) {
            ps.setLong(1, clip.size());
            ps.setLong(2, seconds(clip));
            ps.setString(3, StoredClip.Status.PENDING.db());
            ps.setLong(4, now);
            ps.setString(5, clip.key());
            ps.executeUpdate();
        }
    }

    private static void setRemoved(Connection c, String key, boolean removed, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE clips SET removed = ?, updated_at = ? WHERE key = ?")) {
            ps.setInt(1, removed ? 1 : 0);
            ps.setLong(2, now);
            ps.setString(3, key);
            ps.executeUpdate();
        }
    }

    private static void deleteSubjects(Connection c, String key) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM clip_subjects WHERE clip_key = ?")) {
            ps.setString(1, key);
            ps.executeUpdate();
        }
    }

    private static void requireUpdated(int rows, String key) {
        if (rows != 1) {
            throw new IndexException("Clip not in the index: " + key);
        }
    }

    private static long seconds(Clip clip) {
        return Math.floorDiv(clip.mtimeMillis(), 1000L);
    }

    private <T> T inTransaction(Work<T> work) {
        try {
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IndexException("Index database error: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new IndexException("Cannot close the index: " + e.getMessage(), e);
        }
    }
}
