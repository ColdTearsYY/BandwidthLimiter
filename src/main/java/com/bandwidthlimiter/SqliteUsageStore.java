package com.bandwidthlimiter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/** Owns one SQLite connection on a dedicated serial worker. */
final class SqliteUsageStore implements AutoCloseable {
    static final long SAVE_INTERVAL_SECONDS = 30L;
    private final UsageTracker tracker;
    private final Consumer<Exception> errorHandler;
    private final ScheduledExecutorService worker;
    private final ScheduledFuture<?> periodicSave;
    private Connection connection;
    private Map<UUID, Long> lastSaved = Map.of();
    private boolean closed;

    SqliteUsageStore(Path database, UsageTracker tracker, Consumer<Exception> errorHandler)
            throws IOException {
        this(database, tracker, errorHandler, SAVE_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    // The interval overload also allows deterministic, fast persistence tests.
    SqliteUsageStore(Path database, UsageTracker tracker, Consumer<Exception> errorHandler,
                     long interval, TimeUnit unit) throws IOException {
        if (interval <= 0) throw new IllegalArgumentException("Save interval must be positive");
        this.tracker = tracker;
        this.errorHandler = errorHandler;
        Files.createDirectories(database.toAbsolutePath().getParent());
        worker = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "BandwidthLimiter-SQLite");
            thread.setDaemon(true);
            return thread;
        });
        try {
            // Startup waits for recovery before any player can accumulate usage.
            worker.submit(() -> {
                initialize(database.toAbsolutePath());
                return null;
            }).get();
        } catch (InterruptedException exception) {
            cleanupFailedStartup();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while opening usage database", exception);
        } catch (ExecutionException exception) {
            cleanupFailedStartup();
            throw new IOException("Cannot open usage database: " + database, exception.getCause());
        }
        periodicSave = worker.scheduleWithFixedDelay(() -> {
            try {
                saveSnapshot();
            } catch (Exception exception) {
                errorHandler.accept(exception);
            }
        }, interval, interval, unit);
    }

    synchronized CompletableFuture<Void> flushAsync() {
        CompletableFuture<Void> result = new CompletableFuture<>();
        if (closed) {
            result.completeExceptionally(new IllegalStateException("Usage database is closed"));
            return result;
        }
        worker.execute(() -> {
            try {
                // Capture on the worker, not the caller: queued saves cannot resurrect a reset.
                saveSnapshot();
                result.complete(null);
            } catch (Exception exception) {
                result.completeExceptionally(exception);
                errorHandler.accept(exception);
            }
        });
        return result;
    }

    private void initialize(Path database) throws Exception {
        Class.forName("org.sqlite.JDBC");
        connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = 5000");
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA synchronous = FULL");
            try (ResultSet version = statement.executeQuery("PRAGMA user_version")) {
                if (version.next() && version.getInt(1) > 1) {
                    throw new SQLException("Usage database schema is newer than this plugin");
                }
            }
            statement.execute("CREATE TABLE IF NOT EXISTS player_usage ("
                + "uuid TEXT PRIMARY KEY NOT NULL, "
                + "outbound_bytes INTEGER NOT NULL CHECK(outbound_bytes >= 0))");
            statement.execute("PRAGMA user_version = 1");
            Map<UUID, Long> saved = new HashMap<>();
            try (ResultSet rows = statement.executeQuery("SELECT uuid, outbound_bytes FROM player_usage")) {
                while (rows.next()) {
                    UUID uuid = UUID.fromString(rows.getString(1));
                    long bytes = rows.getLong(2);
                    if (bytes < 0 || rows.wasNull()) {
                        throw new SQLException("Invalid usage record for " + uuid);
                    }
                    saved.put(uuid, bytes);
                }
            }
            tracker.restore(saved);
            lastSaved = Map.copyOf(saved);
        }
    }

    private void saveSnapshot() throws SQLException {
        Map<UUID, Long> snapshot = tracker.snapshot();
        if (snapshot.equals(lastSaved)) return;
        connection.setAutoCommit(false);
        try (PreparedStatement upsert = connection.prepareStatement(
                "INSERT INTO player_usage(uuid, outbound_bytes) VALUES (?, ?) "
                    + "ON CONFLICT(uuid) DO UPDATE SET outbound_bytes = excluded.outbound_bytes");
             PreparedStatement remove = connection.prepareStatement(
                "DELETE FROM player_usage WHERE uuid = ?")) {
            for (Map.Entry<UUID, Long> entry : snapshot.entrySet()) {
                if (!entry.getValue().equals(lastSaved.get(entry.getKey()))) {
                    upsert.setString(1, entry.getKey().toString());
                    upsert.setLong(2, entry.getValue());
                    upsert.addBatch();
                }
            }
            for (UUID uuid : lastSaved.keySet()) {
                if (!snapshot.containsKey(uuid)) {
                    remove.setString(1, uuid.toString());
                    remove.addBatch();
                }
            }
            upsert.executeBatch();
            remove.executeBatch();
            connection.commit();
            lastSaved = snapshot;
        } catch (SQLException exception) {
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                exception.addSuppressed(rollbackFailure);
            }
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private void cleanupFailedStartup() {
        Future<?> cleanup = worker.submit(() -> {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException exception) {
                    errorHandler.accept(exception);
                }
            }
        });
        worker.shutdown();
        try {
            cleanup.get(30, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            errorHandler.accept(exception);
        } catch (ExecutionException | TimeoutException exception) {
            errorHandler.accept(exception);
        }
    }

    @Override
    public void close() throws IOException {
        Future<?> finalSave;
        synchronized (this) {
            if (closed) return;
            closed = true;
            periodicSave.cancel(false);
            finalSave = worker.submit(() -> {
                try (Connection ignored = connection) {
                    saveSnapshot();
                }
                return null;
            });
            worker.shutdown();
        }
        try {
            // Normal shutdown waits for queued saves and the final transaction.
            finalSave.get(30, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while saving usage on shutdown", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new IOException("Cannot finish saving usage on shutdown", exception);
        }
    }
}
