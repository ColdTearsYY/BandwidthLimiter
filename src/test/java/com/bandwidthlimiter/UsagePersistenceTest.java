package com.bandwidthlimiter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class UsagePersistenceTest {
    @TempDir Path directory;
    private final UUID alice = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID bob = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private final List<Exception> errors = new CopyOnWriteArrayList<>();

    private SqliteUsageStore open(UsageTracker tracker) throws IOException {
        return new SqliteUsageStore(directory.resolve("usage.db"), tracker, errors::add);
    }

    @Test
    void reconnectsAndRepeatedSnapshotsDoNotDoubleCount() {
        UsageTracker tracker = new UsageTracker();
        tracker.restore(Map.of(alice, 100L));
        AtomicLong first = new AtomicLong(25L);
        tracker.start(alice, first::get);
        first.addAndGet(5120L);
        assertEquals(5220L, tracker.getTotal(alice));
        assertEquals(5220L, tracker.snapshot().get(alice));
        tracker.stop(alice);
        tracker.stop(alice);
        AtomicLong second = new AtomicLong();
        tracker.start(alice, second::get);
        second.addAndGet(10240L);
        tracker.stop(alice);
        assertEquals(15460L, tracker.getTotal(alice));
    }

    @Test
    void resetRebasesActiveCountersAndPreservesOtherPlayers() {
        UsageTracker tracker = new UsageTracker();
        AtomicLong counter = new AtomicLong();
        tracker.start(alice, counter::get);
        tracker.start(bob, () -> 0L);
        counter.set(500L);
        tracker.reset(alice);
        assertEquals(0L, tracker.getTotal(alice));
        counter.addAndGet(40L);
        assertEquals(40L, tracker.getTotal(alice));
        assertTrue(tracker.snapshot().containsKey(bob));
        tracker.reset(null);
        assertTrue(tracker.snapshot().isEmpty());
        counter.addAndGet(10L);
        assertEquals(10L, tracker.getTotal(alice));
    }

    @Test
    void concurrentSamplingDoesNotDuplicateBytes() throws Exception {
        UsageTracker tracker = new UsageTracker();
        AtomicLong counter = new AtomicLong();
        tracker.start(alice, counter::get);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(5)) {
            List<Future<?>> tasks = new ArrayList<>();
            tasks.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < 10000; i++) counter.incrementAndGet();
                return null;
            }));
            for (int i = 0; i < 4; i++) {
                tasks.add(pool.submit(() -> {
                    start.await();
                    for (int j = 0; j < 2000; j++) {
                        tracker.snapshot();
                        tracker.getTotal(alice);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> task : tasks) task.get(10, TimeUnit.SECONDS);
            tracker.stop(alice);
            assertEquals(10000L, tracker.getTotal(alice));
        }
    }

    @Test
    void savesAndRestores64BitTotalsAcrossReopen() throws Exception {
        UsageTracker tracker = new UsageTracker();
        try (SqliteUsageStore store = open(tracker)) {
            AtomicLong counter = new AtomicLong();
            tracker.start(alice, counter::get);
            counter.set(5_000_000_000L);
            store.flushAsync().get(10, TimeUnit.SECONDS);
            assertEquals(5_000_000_000L, readTotal(alice));
        }
        UsageTracker restored = new UsageTracker();
        try (SqliteUsageStore ignored = open(restored)) {
            assertEquals(5_000_000_000L, restored.getTotal(alice));
        }
        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void shutdownFlushesWithoutExplicitSave() throws Exception {
        UsageTracker tracker = new UsageTracker();
        try (SqliteUsageStore ignored = open(tracker)) {
            AtomicLong counter = new AtomicLong();
            tracker.start(alice, counter::get);
            counter.set(12345L);
        }
        UsageTracker restored = new UsageTracker();
        try (SqliteUsageStore ignored = open(restored)) {
            assertEquals(12345L, restored.getTotal(alice));
        }
        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void periodicSavePersistsWhileStoreIsRunning() throws Exception {
        UsageTracker tracker = new UsageTracker();
        try (SqliteUsageStore ignored = new SqliteUsageStore(directory.resolve("usage.db"),
                tracker, errors::add, 20L, TimeUnit.MILLISECONDS)) {
            AtomicLong counter = new AtomicLong();
            tracker.start(alice, counter::get);
            counter.set(777L);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (readTotal(alice) != 777L && System.nanoTime() < deadline) Thread.sleep(20L);
            assertEquals(777L, readTotal(alice));
        }
        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void queuedSavesCannotRestoreOldUsageAfterSingleAndGlobalReset() throws Exception {
        UsageTracker tracker = new UsageTracker();
        try (SqliteUsageStore store = open(tracker)) {
            AtomicLong first = new AtomicLong();
            AtomicLong second = new AtomicLong();
            tracker.start(alice, first::get);
            tracker.start(bob, second::get);
            first.set(100L);
            second.set(200L);
            store.flushAsync().get(10, TimeUnit.SECONDS);
            List<CompletableFuture<Void>> saves = new ArrayList<>();
            for (int i = 0; i < 20; i++) saves.add(store.flushAsync());
            tracker.reset(alice);
            saves.add(store.flushAsync());
            for (CompletableFuture<Void> save : saves) save.get(10, TimeUnit.SECONDS);
            assertEquals(0L, readTotal(alice));
            assertEquals(200L, readTotal(bob));
            tracker.reset(null);
            store.flushAsync().get(10, TimeUnit.SECONDS);
            assertEquals(0L, readTotal(bob));
            first.addAndGet(7L);
            store.flushAsync().get(10, TimeUnit.SECONDS);
            assertEquals(7L, readTotal(alice));
        }
        UsageTracker restored = new UsageTracker();
        try (SqliteUsageStore ignored = open(restored)) {
            assertEquals(Map.of(alice, 7L), restored.snapshot());
        }
        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void transactionFailureRollsBackAndNextSaveRetries() throws Exception {
        UsageTracker tracker = new UsageTracker();
        try (SqliteUsageStore store = open(tracker)) {
            AtomicLong counter = new AtomicLong();
            tracker.start(alice, counter::get);
            counter.set(100L);
            store.flushAsync().get(10, TimeUnit.SECONDS);
            execute("CREATE TRIGGER reject_usage BEFORE UPDATE ON player_usage "
                + "BEGIN SELECT RAISE(ABORT, 'test write failure'); END");
            counter.set(200L);
            assertThrows(java.util.concurrent.ExecutionException.class,
                () -> store.flushAsync().get(10, TimeUnit.SECONDS));
            assertEquals(100L, readTotal(alice));
            execute("DROP TRIGGER reject_usage");
            store.flushAsync().get(10, TimeUnit.SECONDS);
            assertEquals(200L, readTotal(alice));
        }
        assertEquals(1, errors.size());
    }

    @Test
    void corruptDatabaseIsNotReplacedByEmptyStatistics() throws Exception {
        Path database = directory.resolve("usage.db");
        byte[] invalid = "not a SQLite database".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(database, invalid);
        assertThrows(IOException.class, () -> open(new UsageTracker()));
        assertArrayEquals(invalid, Files.readAllBytes(database));
    }

    @Test
    void newerDatabaseSchemaIsRejected() throws Exception {
        execute("PRAGMA user_version = 2");
        assertThrows(IOException.class, () -> open(new UsageTracker()));
        try (Connection connection = connect(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA user_version")) {
            assertTrue(rows.next());
            assertEquals(2, rows.getInt(1));
        }
    }

    private Connection connect() throws Exception {
        Class.forName("org.sqlite.JDBC");
        return DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("usage.db"));
    }

    private void execute(String sql) throws Exception {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private long readTotal(UUID uuid) throws Exception {
        try (Connection connection = connect(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                 "SELECT outbound_bytes FROM player_usage WHERE uuid = '" + uuid + "'")) {
            return rows.next() ? rows.getLong(1) : 0L;
        }
    }
}
