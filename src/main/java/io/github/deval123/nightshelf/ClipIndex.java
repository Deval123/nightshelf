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
    /** A clip that failed in this many separate runs is left alone unless retries are forced. */
    static final int MAX_ATTEMPTS = 3;
    private static final int BUSY_TIMEOUT_MS = 5000;

    /** One step of the schema; may run Java code (for example a backfill) besides DDL. */
    @FunctionalInterface
    interface Migration {
        void apply(Connection c) throws SQLException;

        static Migration sql(String... statements) {
            return c -> {
                try (Statement s = c.createStatement()) {
                    for (String sql : statements) {
                        s.execute(sql);
                    }
                }
            };
        }
    }

    /** Ordered migrations; entry i takes the schema from version i to i+1. */
    static final List<Migration> MIGRATIONS = List.of(Migration.sql(
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
            "CREATE INDEX clips_status ON clips (status, removed)"),
            Migration.sql("""
            CREATE TABLE clip_embeddings (
                clip_key   TEXT PRIMARY KEY REFERENCES clips (key) ON DELETE CASCADE,
                model      TEXT NOT NULL,
                dim        INTEGER NOT NULL CHECK (dim > 0),
                vector     BLOB NOT NULL CHECK (length(vector) = dim * 4),
                created_at INTEGER NOT NULL
            )"""),
            ClipIndex::createFts,
            Migration.sql(
                    "ALTER TABLE clips ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0",
                    """
                    CREATE TABLE runs (
                        id            INTEGER PRIMARY KEY AUTOINCREMENT,
                        started_at    INTEGER NOT NULL,
                        finished_at   INTEGER,
                        described     INTEGER NOT NULL DEFAULT 0,
                        failed        INTEGER NOT NULL DEFAULT 0,
                        skipped       INTEGER NOT NULL DEFAULT 0,
                        embedded      INTEGER NOT NULL DEFAULT 0,
                        total_seconds REAL NOT NULL DEFAULT 0,
                        avg_seconds   REAL NOT NULL DEFAULT 0,
                        vision_model  TEXT NOT NULL,
                        embed_model   TEXT NOT NULL,
                        stop_reason   TEXT
                    )"""));

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

    static ClipIndex open(Path dbFile, List<Migration> migrations, Clock clock) {
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

    private static void migrate(Connection c, List<Migration> migrations) throws SQLException {
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
            try {
                migrations.get(v).apply(c);
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

    /** Keys of the clips that still need work: pending, and failed ones that are not given up on. */
    public List<String> toProcess() {
        return toProcess(false);
    }

    /**
     * @param retryFailed also return failed clips that failed in {@link #MAX_ATTEMPTS} separate runs
     */
    public List<String> toProcess(boolean retryFailed) {
        return inTransaction(c -> {
            List<String> keys = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT key FROM clips
                    WHERE removed = 0 AND (status = ? OR (status = ? AND (attempts < ? OR ? = 1)))
                    ORDER BY key""")) {
                ps.setString(1, StoredClip.Status.PENDING.db());
                ps.setString(2, StoredClip.Status.FAILED.db());
                ps.setInt(3, MAX_ATTEMPTS);
                ps.setInt(4, retryFailed ? 1 : 0);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        keys.add(rs.getString(1));
                    }
                }
            }
            return keys;
        });
    }

    /** What {@link #sync} would do, without writing anything. */
    public ChangeDetector.Changes preview(List<Clip> scanned) {
        return inTransaction(c -> ChangeDetector.detect(known(c), scanned));
    }

    /** Counts over the index; {@code embedModel} decides which vectors count as current. */
    public IndexTotals totals(String embedModel) {
        return inTransaction(c -> {
            int[] n = new int[7];
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT
                        COALESCE(SUM(CASE WHEN removed = 0 THEN 1 ELSE 0 END), 0),
                        COALESCE(SUM(CASE WHEN removed = 0 AND status = ? THEN 1 ELSE 0 END), 0),
                        COALESCE(SUM(CASE WHEN removed = 0 AND status = ? THEN 1 ELSE 0 END), 0),
                        COALESCE(SUM(CASE WHEN removed = 0 AND status = ? THEN 1 ELSE 0 END), 0),
                        COALESCE(SUM(CASE WHEN removed = 0 AND status = ? AND attempts >= ? THEN 1 ELSE 0 END), 0),
                        COALESCE(SUM(CASE WHEN removed = 1 THEN 1 ELSE 0 END), 0)
                    FROM clips""")) {
                ps.setString(1, StoredClip.Status.PENDING.db());
                ps.setString(2, StoredClip.Status.DESCRIBED.db());
                ps.setString(3, StoredClip.Status.FAILED.db());
                ps.setString(4, StoredClip.Status.FAILED.db());
                ps.setInt(5, MAX_ATTEMPTS);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    for (int i = 0; i < 6; i++) {
                        n[i] = rs.getInt(i + 1);
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT COUNT(*) FROM clip_embeddings e JOIN clips c ON c.key = e.clip_key
                    WHERE e.model = ? AND c.removed = 0 AND c.status = ?""")) {
                ps.setString(1, embedModel);
                ps.setString(2, StoredClip.Status.DESCRIBED.db());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    n[6] = rs.getInt(1);
                }
            }
            return new IndexTotals(n[0], n[1], n[2], n[3], n[4], n[6], n[5]);
        });
    }

    // ---- runs -------------------------------------------------------------

    /** Opens a run record; it stays unfinished if the process is killed. */
    public long startRun(String visionModel, String embedModel) {
        return inTransaction(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO runs (started_at, vision_model, embed_model) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, clock.millis());
                ps.setString(2, visionModel);
                ps.setString(3, embedModel);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }

    public void finishRun(long id, int described, int failed, int skipped, int embedded, double totalSeconds,
            double avgSecondsPerClip, String stopReason) {
        inTransaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    UPDATE runs SET finished_at = ?, described = ?, failed = ?, skipped = ?, embedded = ?,
                        total_seconds = ?, avg_seconds = ?, stop_reason = ?
                    WHERE id = ?""")) {
                ps.setLong(1, clock.millis());
                ps.setInt(2, described);
                ps.setInt(3, failed);
                ps.setInt(4, skipped);
                ps.setInt(5, embedded);
                ps.setDouble(6, totalSeconds);
                ps.setDouble(7, avgSecondsPerClip);
                ps.setString(8, stopReason);
                ps.setLong(9, id);
                requireUpdated(ps.executeUpdate(), "run " + id);
            }
            return null;
        });
    }

    public Optional<RunRecord> lastRun() {
        return inTransaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT id, started_at, finished_at, described, failed, skipped, embedded, total_seconds,
                        avg_seconds, vision_model, embed_model, stop_reason
                    FROM runs ORDER BY id DESC LIMIT 1""");
                    ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new RunRecord(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getInt(4),
                        rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getDouble(8), rs.getDouble(9),
                        rs.getString(10), rs.getString(11), rs.getString(12)));
            }
        });
    }

    /** Stores the result for one clip in its own transaction. */
    public void recordDescribed(String key, Description d) {
        inTransaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                UPDATE clips SET status = ?, description = ?, setting = ?, time_of_day = ?, mood = ?,
                failure_reason = NULL, attempts = 0, updated_at = ? WHERE key = ?
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
            deleteEmbedding(c, key);
            syncFts(c, key);
            return null;
        });
    }

    /** Records a per-clip failure; the reason is sanitized and capped. */
    public void recordFailed(String key, String reason) {
        inTransaction(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE clips SET status = ?, failure_reason = ?, attempts = attempts + 1, updated_at = ? WHERE key = ?")) {
                ps.setString(1, StoredClip.Status.FAILED.db());
                ps.setString(2, Sanitize.clean(reason, MAX_FAILURE_REASON));
                ps.setLong(3, clock.millis());
                ps.setString(4, key);
                requireUpdated(ps.executeUpdate(), key);
            }
            syncFts(c, key);
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

    // ---- embeddings -------------------------------------------------------

    /** Described, present clips that have no vector for this embedding model (none, or one from another model). */
    public List<String> toEmbed(String model) {
        return inTransaction(c -> {
            List<String> keys = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT c.key FROM clips c
                    LEFT JOIN clip_embeddings e ON e.clip_key = c.key AND e.model = ?
                    WHERE c.status = ? AND c.removed = 0 AND e.clip_key IS NULL
                    ORDER BY c.key""")) {
                ps.setString(1, model);
                ps.setString(2, StoredClip.Status.DESCRIBED.db());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        keys.add(rs.getString(1));
                    }
                }
            }
            return keys;
        });
    }

    /** The dimension of the vectors stored for this model, or null if there are none. */
    public Integer embeddingDimension(String model) {
        return inTransaction(c -> embeddingDimension(c, model));
    }

    private static Integer embeddingDimension(Connection c, String model) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT dim FROM clip_embeddings WHERE model = ? LIMIT 1")) {
            ps.setString(1, model);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : null;
            }
        }
    }

    /** Stores (or replaces) the vector of one clip in its own transaction. */
    public void recordEmbedding(String key, String model, float[] vector) {
        if (vector.length == 0 || !Vectors.allFinite(vector)) {
            throw new IndexException("Refusing to store an empty or non-finite vector for " + key);
        }
        inTransaction(c -> {
            Integer existing = embeddingDimension(c, model);
            if (existing != null && existing != vector.length) {
                throw new IndexException("Vector for " + key + " has " + vector.length
                        + " dimensions but the index uses " + existing + " for model " + model);
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO clip_embeddings (clip_key, model, dim, vector, created_at) VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (clip_key) DO UPDATE SET model = excluded.model, dim = excluded.dim,
                        vector = excluded.vector, created_at = excluded.created_at""")) {
                ps.setString(1, key);
                ps.setString(2, model);
                ps.setInt(3, vector.length);
                ps.setBytes(4, Vectors.toBlob(vector));
                ps.setLong(5, clock.millis());
                ps.executeUpdate();
            } catch (SQLException e) {
                if (e.getMessage() != null && e.getMessage().contains("FOREIGN KEY")) {
                    throw new IndexException("Clip not in the index: " + key);
                }
                throw e;
            }
            return null;
        });
    }

    /** Current vectors (this model only) of described clips that are not removed, by key. */
    public Map<String, float[]> currentEmbeddings(String model) {
        return inTransaction(c -> {
            Map<String, float[]> out = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT e.clip_key, e.vector FROM clip_embeddings e
                    JOIN clips c ON c.key = e.clip_key
                    WHERE e.model = ? AND c.status = ? AND c.removed = 0""")) {
                ps.setString(1, model);
                ps.setString(2, StoredClip.Status.DESCRIBED.db());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.put(rs.getString(1), Vectors.fromBlob(rs.getBytes(2)));
                    }
                }
            }
            return out;
        });
    }

    private static void deleteEmbedding(Connection c, String key) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM clip_embeddings WHERE clip_key = ?")) {
            ps.setString(1, key);
            ps.executeUpdate();
        }
    }

    // ---- full-text search -------------------------------------------------

    /**
     * Keys of matching clips, best first (ties by key). The text is turned into
     * quoted words by {@link FtsQuery}, so it can never act as FTS syntax.
     * Removed and undescribed clips are never returned.
     */
    public List<String> ftsSearch(String userText, int limit) {
        String match = FtsQuery.match(userText);
        if (match == null) {
            return List.of();
        }
        return inTransaction(c -> {
            List<String> keys = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT clips_fts.key FROM clips_fts
                    JOIN clips c ON c.key = clips_fts.key
                    WHERE clips_fts MATCH ? AND c.removed = 0 AND c.status = ?
                    ORDER BY bm25(clips_fts), clips_fts.key
                    LIMIT ?""")) {
                ps.setString(1, match);
                ps.setString(2, StoredClip.Status.DESCRIBED.db());
                ps.setInt(3, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        keys.add(rs.getString(1));
                    }
                }
            }
            return keys;
        });
    }

    /** Migration 3: the FTS5 table, filled from the clips that are already described. */
    private static void createFts(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("""
                    CREATE VIRTUAL TABLE clips_fts USING fts5 (
                        key UNINDEXED, body, tokenize = "unicode61 remove_diacritics 2")""");
        }
        List<String> keys = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT key FROM clips WHERE status = ? AND removed = 0");) {
            ps.setString(1, StoredClip.Status.DESCRIBED.db());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    keys.add(rs.getString(1));
                }
            }
        }
        for (String key : keys) {
            syncFts(c, key);
        }
    }

    /**
     * Makes the FTS row of one clip match the clips table: present when the
     * clip is described and not removed, absent otherwise. Called by every
     * write that can change that.
     */
    private static void syncFts(Connection c, String key) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM clips_fts WHERE key = ?")) {
            ps.setString(1, key);
            ps.executeUpdate();
        }
        String body = null;
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT description, setting, time_of_day, mood FROM clips
                WHERE key = ? AND status = ? AND removed = 0 AND description IS NOT NULL""")) {
            ps.setString(1, key);
            ps.setString(2, StoredClip.Status.DESCRIBED.db());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    body = new Description(rs.getString(1), subjects(c, key), rs.getString(2),
                            rs.getString(3), rs.getString(4)).searchText();
                }
            }
        }
        if (body != null) {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO clips_fts (key, body) VALUES (?, ?)")) {
                ps.setString(1, key);
                ps.setString(2, body);
                ps.executeUpdate();
            }
        }
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
        deleteEmbedding(c, clip.key());
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE clips SET size = ?, mtime = ?, status = ?, description = NULL, setting = NULL,
                time_of_day = NULL, mood = NULL, failure_reason = NULL, attempts = 0, removed = 0, updated_at = ?
                WHERE key = ?
                """)) {
            ps.setLong(1, clip.size());
            ps.setLong(2, seconds(clip));
            ps.setString(3, StoredClip.Status.PENDING.db());
            ps.setLong(4, now);
            ps.setString(5, clip.key());
            ps.executeUpdate();
        }
        syncFts(c, clip.key());
    }

    private static void setRemoved(Connection c, String key, boolean removed, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE clips SET removed = ?, updated_at = ? WHERE key = ?")) {
            ps.setInt(1, removed ? 1 : 0);
            ps.setLong(2, now);
            ps.setString(3, key);
            ps.executeUpdate();
        }
        syncFts(c, key);
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
