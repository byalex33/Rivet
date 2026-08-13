package dev.rivet;

import java.io.Closeable;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

final class AuditStorage implements Closeable {
    private static final int BATCH_SIZE = 128;
    private static final long FLUSH_MILLIS = 250;
    private static final String INSERT = """
        INSERT INTO audit_log(timestamp, player_uuid, player_name, action, world, x, y, z,
                              target, amount, before_data, after_data, metadata)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

    private final Connection connection;
    private final ConcurrentLinkedQueue<AuditEntry> pending = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService executor;
    private final Consumer<Throwable> errorHandler;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger pendingCount = new AtomicInteger();

    AuditStorage(Path database, Consumer<Throwable> errorHandler) throws SQLException {
        this.errorHandler = errorHandler;
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException exception) {
            throw new SQLException("SQLite JDBC driver is unavailable", exception);
        }
        connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
        initialize();
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Rivet-Audit-Storage");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::safeFlush, FLUSH_MILLIS, FLUSH_MILLIS,
            TimeUnit.MILLISECONDS);
    }

    private void initialize() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("""
                CREATE TABLE IF NOT EXISTS audit_log (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    timestamp INTEGER NOT NULL,
                    player_uuid TEXT,
                    player_name TEXT,
                    action TEXT NOT NULL,
                    world TEXT NOT NULL,
                    x INTEGER NOT NULL,
                    y INTEGER NOT NULL,
                    z INTEGER NOT NULL,
                    target TEXT NOT NULL,
                    amount INTEGER,
                    before_data TEXT,
                    after_data TEXT,
                    metadata TEXT
                )
                """);
            statement.execute("CREATE INDEX IF NOT EXISTS audit_time_idx ON audit_log(timestamp DESC)");
            statement.execute("CREATE INDEX IF NOT EXISTS audit_player_idx ON audit_log(player_name COLLATE NOCASE, timestamp DESC)");
            statement.execute("CREATE INDEX IF NOT EXISTS audit_location_idx ON audit_log(world, x, y, z, timestamp DESC)");
            statement.execute("CREATE INDEX IF NOT EXISTS audit_action_idx ON audit_log(action, timestamp DESC)");
        }
    }

    void append(AuditEntry entry) {
        if (closed.get()) {
            return;
        }
        pending.add(entry);
        if (pendingCount.incrementAndGet() >= BATCH_SIZE) {
            executor.execute(this::safeFlush);
        }
    }

    CompletableFuture<List<AuditEntry>> query(AuditQuery query) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                flushNow();
                return queryNow(query);
            } catch (SQLException exception) {
                throw new AuditStorageException(exception);
            }
        }, executor);
    }

    CompletableFuture<Integer> count(AuditQuery query) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                flushNow();
                return countNow(query);
            } catch (SQLException exception) {
                throw new AuditStorageException(exception);
            }
        }, executor);
    }

    CompletableFuture<Integer> purgeBefore(long cutoff) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                flushNow();
                try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM audit_log WHERE timestamp < ?")) {
                    statement.setLong(1, cutoff);
                    return statement.executeUpdate();
                }
            } catch (SQLException exception) {
                throw new AuditStorageException(exception);
            }
        }, executor);
    }

    void flush() throws SQLException {
        if (closed.get()) {
            return;
        }
        try {
            executor.submit(() -> {
                try {
                    flushNow();
                } catch (SQLException exception) {
                    throw new AuditStorageException(exception);
                }
            }).get(10, TimeUnit.SECONDS);
        } catch (AuditStorageException exception) {
            throw (SQLException) exception.getCause();
        } catch (Exception exception) {
            throw new SQLException("Could not flush audit entries", exception);
        }
    }

    private void safeFlush() {
        try {
            flushNow();
        } catch (SQLException exception) {
            errorHandler.accept(exception);
        }
    }

    private void flushNow() throws SQLException {
        List<AuditEntry> batch = new ArrayList<>(BATCH_SIZE);
        AuditEntry entry;
        while (batch.size() < BATCH_SIZE && (entry = pending.poll()) != null) {
            pendingCount.decrementAndGet();
            batch.add(entry);
        }
        while (!batch.isEmpty()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
                for (AuditEntry queued : batch) {
                    bindEntry(statement, queued);
                    statement.addBatch();
                }
                statement.executeBatch();
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                batch.forEach(queued -> {
                    pending.add(queued);
                    pendingCount.incrementAndGet();
                });
                throw exception;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
            batch.clear();
            while (batch.size() < BATCH_SIZE && (entry = pending.poll()) != null) {
                pendingCount.decrementAndGet();
                batch.add(entry);
            }
        }
    }

    private static void bindEntry(PreparedStatement statement, AuditEntry entry)
        throws SQLException {
        statement.setLong(1, entry.timestamp());
        statement.setString(2, entry.playerUuid() == null ? null : entry.playerUuid().toString());
        statement.setString(3, entry.playerName());
        statement.setString(4, entry.action().name());
        statement.setString(5, entry.world());
        statement.setInt(6, entry.x());
        statement.setInt(7, entry.y());
        statement.setInt(8, entry.z());
        statement.setString(9, entry.target());
        if (entry.amount() == null) {
            statement.setNull(10, java.sql.Types.INTEGER);
        } else {
            statement.setInt(10, entry.amount());
        }
        statement.setString(11, entry.beforeData());
        statement.setString(12, entry.afterData());
        statement.setString(13, entry.metadata());
    }

    private List<AuditEntry> queryNow(AuditQuery query) throws SQLException {
        SqlFilter filter = filter(query);
        String sql = "SELECT * FROM audit_log" + filter.where()
            + " ORDER BY timestamp DESC, id DESC LIMIT ? OFFSET ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = filter.bind(statement);
            statement.setInt(index++, query.limit());
            statement.setInt(index, query.offset());
            try (ResultSet results = statement.executeQuery()) {
                List<AuditEntry> entries = new ArrayList<>();
                while (results.next()) {
                    String uuid = results.getString("player_uuid");
                    int amount = results.getInt("amount");
                    entries.add(new AuditEntry(results.getLong("id"),
                        results.getLong("timestamp"), uuid == null ? null : UUID.fromString(uuid),
                        results.getString("player_name"),
                        AuditAction.valueOf(results.getString("action")),
                        results.getString("world"), results.getInt("x"), results.getInt("y"),
                        results.getInt("z"), results.getString("target"),
                        results.wasNull() ? null : amount, results.getString("before_data"),
                        results.getString("after_data"), results.getString("metadata")));
                }
                return List.copyOf(entries);
            }
        }
    }

    private int countNow(AuditQuery query) throws SQLException {
        SqlFilter filter = filter(query);
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT COUNT(*) FROM audit_log" + filter.where())) {
            filter.bind(statement);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        }
    }

    private static SqlFilter filter(AuditQuery query) {
        List<String> clauses = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        clauses.add("timestamp >= ?");
        values.add(query.since());
        if (query.playerName() != null) {
            clauses.add("player_name = ? COLLATE NOCASE");
            values.add(query.playerName());
        }
        if (query.world() != null) {
            clauses.add("world = ?");
            values.add(query.world());
        }
        if (query.x() != null && query.y() != null && query.z() != null) {
            if (query.radius() == null || query.radius() == 0) {
                clauses.add("x = ? AND y = ? AND z = ?");
                values.add(query.x());
                values.add(query.y());
                values.add(query.z());
            } else {
                clauses.add("x BETWEEN ? AND ? AND y BETWEEN ? AND ? AND z BETWEEN ? AND ?");
                values.add(query.x() - query.radius());
                values.add(query.x() + query.radius());
                values.add(query.y() - query.radius());
                values.add(query.y() + query.radius());
                values.add(query.z() - query.radius());
                values.add(query.z() + query.radius());
                clauses.add("((x - ?) * (x - ?) + (y - ?) * (y - ?) + "
                    + "(z - ?) * (z - ?)) <= ?");
                values.add(query.x());
                values.add(query.x());
                values.add(query.y());
                values.add(query.y());
                values.add(query.z());
                values.add(query.z());
                values.add(query.radius() * query.radius());
            }
        }
        if (!query.actions().isEmpty()) {
            StringJoiner placeholders = new StringJoiner(", ", "action IN (", ")");
            query.actions().forEach(action -> {
                placeholders.add("?");
                values.add(action.name());
            });
            clauses.add(placeholders.toString());
        }
        if (!query.includeCommands()) {
            clauses.add("action <> 'COMMAND'");
        }
        return new SqlFilter(" WHERE " + String.join(" AND ", clauses), values);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        Future<?> finalFlush = executor.submit(this::safeFlush);
        executor.shutdown();
        try {
            finalFlush.get(30, TimeUnit.SECONDS);
            if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                errorHandler.accept(new SQLException("Timed out waiting for audit writes"));
                return;
            }
            connection.close();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            errorHandler.accept(exception);
        } catch (java.util.concurrent.ExecutionException
                 | java.util.concurrent.TimeoutException exception) {
            errorHandler.accept(exception);
        } catch (SQLException exception) {
            errorHandler.accept(exception);
        }
    }

    private record SqlFilter(String where, List<Object> values) {
        int bind(PreparedStatement statement) throws SQLException {
            int index = 1;
            for (Object value : values) {
                if (value instanceof Long number) {
                    statement.setLong(index++, number);
                } else if (value instanceof Integer number) {
                    statement.setInt(index++, number);
                } else {
                    statement.setString(index++, String.valueOf(value));
                }
            }
            return index;
        }
    }

    static final class AuditStorageException extends RuntimeException {
        AuditStorageException(SQLException cause) {
            super(cause);
        }
    }
}
