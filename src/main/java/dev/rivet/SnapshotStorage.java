package dev.rivet;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

final class SnapshotStorage implements Closeable {
    private final Connection connection;
    private final ExecutorService executor;
    private final Consumer<Throwable> errorHandler;

    SnapshotStorage(Path database, Consumer<Throwable> errorHandler) throws SQLException {
        this.errorHandler = errorHandler;
        try {
            Class.forName("org.sqlite.JDBC");
            Path parent = database.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (ClassNotFoundException | IOException exception) {
            throw new SQLException("Could not prepare snapshot storage", exception);
        }
        connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
        initialize();
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Rivet-Snapshot-Storage");
            thread.setDaemon(true);
            return thread;
        });
    }

    CompletableFuture<SnapshotRecord> save(CapturedSnapshot captured,
                                           SnapshotSettings settings) {
        return supply(() -> saveNow(captured, settings));
    }

    CompletableFuture<List<SnapshotRecord>> list(UUID player, int limit) {
        return supply(() -> listNow(player, limit));
    }

    CompletableFuture<SnapshotRecord> load(long id) {
        return supply(() -> loadNow(id));
    }

    CompletableFuture<UUID> findPlayer(String name) {
        return supply(() -> findPlayerNow(name));
    }

    CompletableFuture<CleanupResult> cleanup(SnapshotSettings settings) {
        return supply(() -> cleanupNow(System.currentTimeMillis()
            - Duration.ofDays(settings.retentionDays()).toMillis(), settings.maxPerPlayer()));
    }

    SnapshotRecord saveNow(CapturedSnapshot captured, SnapshotSettings settings)
        throws SQLException, IOException {
        byte[] encoded = SnapshotCodec.encode(captured.state());
        String hash = SnapshotCodec.hash(encoded);
        String blobKey = settings.deduplicate() ? hash : hash + ":" + UUID.randomUUID();
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            try (PreparedStatement blob = connection.prepareStatement(
                "INSERT OR IGNORE INTO snapshot_blobs(blob_key, content_hash, data, size, created_at) VALUES (?, ?, ?, ?, ?)")) {
                blob.setString(1, blobKey);
                blob.setString(2, hash);
                blob.setBytes(3, encoded);
                blob.setInt(4, encoded.length);
                blob.setLong(5, captured.timestamp());
                blob.executeUpdate();
            }
            long id;
            try (PreparedStatement snapshot = connection.prepareStatement("""
                INSERT INTO snapshots(player_uuid, player_name, reason, timestamp, world,
                                      x, y, z, death_cause, blob_key)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
                snapshot.setString(1, captured.playerUuid().toString());
                snapshot.setString(2, captured.playerName());
                snapshot.setString(3, captured.reason());
                snapshot.setLong(4, captured.timestamp());
                snapshot.setString(5, captured.world());
                snapshot.setDouble(6, captured.x());
                snapshot.setDouble(7, captured.y());
                snapshot.setDouble(8, captured.z());
                snapshot.setString(9, captured.deathCause());
                snapshot.setString(10, blobKey);
                snapshot.executeUpdate();
                try (ResultSet keys = snapshot.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new SQLException("Snapshot insert did not return an ID");
                    }
                    id = keys.getLong(1);
                }
            }
            cleanupPlayer(captured.playerUuid(), settings.maxPerPlayer());
            connection.commit();
            return new SnapshotRecord(id, captured.playerUuid(), captured.playerName(),
                captured.reason(), captured.timestamp(), captured.world(), captured.x(),
                captured.y(), captured.z(), captured.deathCause(), blobKey, captured.state());
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    List<SnapshotRecord> listNow(UUID player, int limit) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT * FROM snapshots WHERE player_uuid = ?
            ORDER BY timestamp DESC, id DESC LIMIT ?
            """)) {
            statement.setString(1, player.toString());
            statement.setInt(2, Math.max(1, limit));
            try (ResultSet results = statement.executeQuery()) {
                List<SnapshotRecord> records = new ArrayList<>();
                while (results.next()) {
                    records.add(record(results, null));
                }
                return List.copyOf(records);
            }
        }
    }

    SnapshotRecord loadNow(long id) throws SQLException, IOException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT s.*, b.data FROM snapshots s
            JOIN snapshot_blobs b ON b.blob_key = s.blob_key
            WHERE s.id = ?
            """)) {
            statement.setLong(1, id);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? record(results, SnapshotCodec.decode(results.getBytes("data"))) : null;
            }
        }
    }

    UUID findPlayerNow(String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT player_uuid FROM snapshots WHERE player_name = ? COLLATE NOCASE
            ORDER BY timestamp DESC, id DESC LIMIT 1
            """)) {
            statement.setString(1, name);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? UUID.fromString(results.getString(1)) : null;
            }
        }
    }

    CleanupResult cleanupNow(long cutoff, int maxPerPlayer) throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            int expired = deleteExpired(cutoff);
            int excess;
            try (PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM snapshots WHERE id IN (
                    SELECT id FROM (
                        SELECT id, ROW_NUMBER() OVER (
                            PARTITION BY player_uuid ORDER BY timestamp DESC, id DESC
                        ) AS row_number FROM snapshots
                    ) ranked WHERE row_number > ?
                )
                """)) {
                statement.setInt(1, Math.max(1, maxPerPlayer));
                excess = statement.executeUpdate();
            }
            int blobs = deleteOrphanBlobs();
            connection.commit();
            return new CleanupResult(expired, excess, blobs);
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    int blobCountNow() throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM snapshot_blobs")) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    int snapshotCountNow() throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM snapshots")) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            executor.awaitTermination(10, TimeUnit.SECONDS);
            connection.close();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (SQLException exception) {
            errorHandler.accept(exception);
        }
    }

    private void initialize() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("""
                CREATE TABLE IF NOT EXISTS snapshot_blobs (
                    blob_key TEXT PRIMARY KEY,
                    content_hash TEXT NOT NULL,
                    data BLOB NOT NULL,
                    size INTEGER NOT NULL,
                    created_at INTEGER NOT NULL
                )
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS snapshots (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    player_uuid TEXT NOT NULL,
                    player_name TEXT NOT NULL,
                    reason TEXT NOT NULL,
                    timestamp INTEGER NOT NULL,
                    world TEXT NOT NULL,
                    x REAL NOT NULL,
                    y REAL NOT NULL,
                    z REAL NOT NULL,
                    death_cause TEXT,
                    blob_key TEXT NOT NULL REFERENCES snapshot_blobs(blob_key)
                )
                """);
            statement.execute("CREATE INDEX IF NOT EXISTS snapshot_player_time_idx ON snapshots(player_uuid, timestamp DESC, id DESC)");
            statement.execute("CREATE INDEX IF NOT EXISTS snapshot_time_idx ON snapshots(timestamp)");
            statement.execute("CREATE INDEX IF NOT EXISTS snapshot_name_idx ON snapshots(player_name COLLATE NOCASE, timestamp DESC)");
            statement.execute("CREATE INDEX IF NOT EXISTS snapshot_blob_idx ON snapshots(blob_key)");
        }
    }

    private void cleanupPlayer(UUID player, int maximum) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            DELETE FROM snapshots WHERE id IN (
                SELECT id FROM snapshots WHERE player_uuid = ?
                ORDER BY timestamp DESC, id DESC LIMIT -1 OFFSET ?
            )
            """)) {
            statement.setString(1, player.toString());
            statement.setInt(2, Math.max(1, maximum));
            statement.executeUpdate();
        }
    }

    private int deleteExpired(long cutoff) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "DELETE FROM snapshots WHERE timestamp < ?")) {
            statement.setLong(1, cutoff);
            return statement.executeUpdate();
        }
    }

    private int deleteOrphanBlobs() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            return statement.executeUpdate("""
                DELETE FROM snapshot_blobs WHERE NOT EXISTS (
                    SELECT 1 FROM snapshots WHERE snapshots.blob_key = snapshot_blobs.blob_key
                )
                """);
        }
    }

    private static SnapshotRecord record(ResultSet results, SnapshotState state)
        throws SQLException {
        return new SnapshotRecord(results.getLong("id"),
            UUID.fromString(results.getString("player_uuid")), results.getString("player_name"),
            results.getString("reason"), results.getLong("timestamp"), results.getString("world"),
            results.getDouble("x"), results.getDouble("y"), results.getDouble("z"),
            results.getString("death_cause"), results.getString("blob_key"), state);
    }

    private <T> CompletableFuture<T> supply(CheckedSupplier<T> supplier) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return supplier.get();
            } catch (Exception exception) {
                throw new SnapshotStorageException(exception);
            }
        }, executor).whenComplete((result, throwable) -> {
            if (throwable != null) {
                errorHandler.accept(throwable);
            }
        });
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    record CleanupResult(int expiredSnapshots, int excessSnapshots, int orphanedBlobs) {
    }

    static final class SnapshotStorageException extends RuntimeException {
        SnapshotStorageException(Throwable cause) {
            super(cause);
        }
    }
}
