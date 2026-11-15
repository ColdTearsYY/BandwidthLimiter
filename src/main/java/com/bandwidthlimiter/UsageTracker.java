package com.bandwidthlimiter;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Thread-safe accounting; only reads counters, never performs disk I/O. */
final class UsageTracker {
    private final Map<UUID, Long> totals = new HashMap<>();
    private final Map<UUID, Session> sessions = new HashMap<>();

    synchronized void restore(Map<UUID, Long> saved) {
        if (!totals.isEmpty() || !sessions.isEmpty()) {
            throw new IllegalStateException("Usage must be restored before tracking players");
        }
        saved.forEach((uuid, bytes) -> {
            if (uuid == null || bytes == null || bytes < 0) {
                throw new IllegalArgumentException("Invalid saved usage");
            }
        });
        totals.putAll(saved);
    }

    synchronized void start(UUID uuid, LongSupplier counter) {
        settle(uuid);
        sessions.put(uuid, new Session(counter, counter.getAsLong()));
        totals.putIfAbsent(uuid, 0L);
    }

    synchronized void stop(UUID uuid) {
        settle(uuid);
        sessions.remove(uuid);
    }

    synchronized long getTotal(UUID uuid) {
        settle(uuid);
        return totals.getOrDefault(uuid, 0L);
    }

    synchronized Map<UUID, Long> snapshot() {
        sessions.keySet().forEach(this::settle);
        return Map.copyOf(totals);
    }

    synchronized void reset(UUID uuid) {
        if (uuid == null) {
            totals.clear();
            sessions.values().forEach(Session::rebase);
        } else {
            totals.remove(uuid);
            Session session = sessions.get(uuid);
            if (session != null) session.rebase();
        }
    }

    private void settle(UUID uuid) {
        Session session = sessions.get(uuid);
        if (session == null) return;
        long current = session.counter.getAsLong();
        long delta = Math.max(0L, current - session.baseline);
        if (delta > 0) {
            long previous = totals.getOrDefault(uuid, 0L);
            totals.put(uuid, delta > Long.MAX_VALUE - previous ? Long.MAX_VALUE : previous + delta);
        }
        session.baseline = current;
    }

    private static final class Session {
        private final LongSupplier counter;
        private long baseline;

        private Session(LongSupplier counter, long baseline) {
            this.counter = counter;
            this.baseline = baseline;
        }

        private void rebase() {
            baseline = counter.getAsLong();
        }
    }
}
