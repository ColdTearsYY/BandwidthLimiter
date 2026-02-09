package com.bandwidthlimiter;

import io.netty.handler.traffic.ChannelTrafficShapingHandler;

import java.util.UUID;

/**
 * 玩家带宽处理器 - 基于 Netty 的 ChannelTrafficShapingHandler
 *
 * 工作原理:
 * ChannelTrafficShapingHandler 是 Netty 内置的流量整形处理器。
 * 它通过延迟写操作来限制出站带宽，通过延迟读操作来限制入站带宽。
 *
 * 当出站速率超过设定的 writeLimit 时，后续的写操作会被排队等待，
 * 直到当前时间窗口内的发送量低于限制值。
 *
 * 这是在 Netty 层面的真实字节级带宽控制，比基于数据包计数的限制更精确。
 */
public class PlayerBandwidthHandler extends ChannelTrafficShapingHandler {

    private final UUID playerUuid;

    /**
     * @param playerUuid    玩家 UUID
     * @param readLimit     入站限制 (bytes/s), 0 = 不限制
     * @param writeLimit    出站限制 (bytes/s), 0 = 不限制
     * @param checkInterval 检查间隔 (ms)
     */
    public PlayerBandwidthHandler(UUID playerUuid, long readLimit, long writeLimit, long checkInterval) {
        super(writeLimit, readLimit, checkInterval);
        this.playerUuid = playerUuid;
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    /**
     * 获取当前出站速率 (bytes/s)
     */
    public long getCurrentWriteRate() {
        return trafficCounter().lastWrittenBytes();
    }

    /**
     * 获取当前入站速率 (bytes/s)
     */
    public long getCurrentReadRate() {
        return trafficCounter().lastReadBytes();
    }

    /**
     * 获取累计出站总量 (bytes)
     */
    public long getTotalWritten() {
        return trafficCounter().cumulativeWrittenBytes();
    }

    /**
     * 获取累计入站总量 (bytes)
     */
    public long getTotalRead() {
        return trafficCounter().cumulativeReadBytes();
    }

    /**
     * 更新出站限制
     */
    public void setWriteLimit(long writeLimit) {
        configure(writeLimit, getReadLimit());
    }

    /**
     * 更新入站限制
     */
    public void setReadLimit(long readLimit) {
        configure(getWriteLimit(), readLimit);
    }

    /**
     * 获取当前出站限制
     */
    public long getWriteLimit() {
        return trafficCounter().lastWrittenBytes() >= 0 ? super.getWriteLimit() : 0;
    }

    /**
     * 获取当前入站限制
     */
    public long getReadLimit() {
        return super.getReadLimit();
    }
}