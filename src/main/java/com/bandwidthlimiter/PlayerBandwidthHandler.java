package com.bandwidthlimiter;

import io.netty.handler.traffic.ChannelTrafficShapingHandler;

import java.util.UUID;

/**
 * Per-player outbound traffic shaping handler.
 * The handler is inserted before Minecraft's encoder so outbound events reach
 * the handler after packet encoding and are measured as bytes.
 */
public final class PlayerBandwidthHandler extends ChannelTrafficShapingHandler {

    private final UUID playerUuid;
    private volatile long writeLimit;
    private volatile long readLimit;

    public PlayerBandwidthHandler(UUID playerUuid, long readLimit, long writeLimit, long checkInterval) {
        super(writeLimit, readLimit, checkInterval);
        this.playerUuid = playerUuid;
        this.writeLimit = writeLimit;
        this.readLimit = readLimit;
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public long getCurrentWriteRate() {
        return trafficCounter().lastWrittenBytes();
    }

    public long getCurrentReadRate() {
        return trafficCounter().lastReadBytes();
    }

    public long getTotalWritten() {
        return trafficCounter().cumulativeWrittenBytes();
    }

    public long getTotalRead() {
        return trafficCounter().cumulativeReadBytes();
    }

    public void setWriteLimit(long writeLimit) {
        this.writeLimit = writeLimit;
        configure(writeLimit, readLimit);
    }

    public void setReadLimit(long readLimit) {
        this.readLimit = readLimit;
        configure(writeLimit, readLimit);
    }

    public long getWriteLimit() {
        return writeLimit;
    }

    public long getReadLimit() {
        return readLimit;
    }
}
