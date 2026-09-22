package com.bandwidthlimiter;

import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Manages per-player Netty traffic shaping handlers. */
public final class BandwidthManager {

    private static final String HANDLER_NAME = "bandwidth_limiter";
    private static final long CHECK_INTERVAL_MILLIS = 1_000L;

    private final BandwidthLimiterPlugin plugin;
    private final Map<UUID, Boolean> injectionPending = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerBandwidthHandler> handlers = new ConcurrentHashMap<>();
    private final Map<UUID, Channel> channels = new ConcurrentHashMap<>();
    private final Map<UUID, Long> playerLimits = new ConcurrentHashMap<>();
    private volatile long defaultLimitKBps = 512L;

    public BandwidthManager(BandwidthLimiterPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadConfig() {
        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();
        defaultLimitKBps = positiveLimit(config.getLong("default-limit-kbps", 512L), 512L);

        playerLimits.clear();
        ConfigurationSection section = config.getConfigurationSection("player-limits");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                long limit = positiveLimit(section.getLong(key, -1L), -1L);
                if (limit <= 0) {
                    plugin.getLogger().warning("忽略无效的玩家带宽限制: player-limits." + key);
                    continue;
                }
                try {
                    playerLimits.put(UUID.fromString(key), limit);
                } catch (IllegalArgumentException ignored) {
                    Player player = Bukkit.getPlayerExact(key);
                    if (player != null) {
                        playerLimits.put(player.getUniqueId(), limit);
                    } else {
                        plugin.getLogger().warning("玩家名配置仅在玩家在线时可解析: " + key);
                    }
                }
            }
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            updatePlayerLimit(player);
        }
        plugin.getLogger().info("配置已重新加载 - 默认限制: " + defaultLimitKBps + " KB/s");
    }

    /** Resolves CraftPlayer -> ServerPlayer -> Connection -> Netty Channel. */
    public Channel getPlayerChannel(Player player) {
        try {
            Object serverPlayer = invokeNoArg(player, "getHandle");
            if (serverPlayer == null) {
                return null;
            }

            Object connection = getFieldByTypeName(serverPlayer,
                "net.minecraft.server.network.ServerGamePacketListenerImpl");
            if (connection == null) {
                connection = getFieldValue(serverPlayer, "connection");
            }
            if (connection == null) {
                warnChannelFailure(player, "connection");
                return null;
            }

            Object networkConnection = getFieldByTypeName(connection,
                "net.minecraft.network.Connection");
            if (networkConnection == null) {
                networkConnection = getFieldValue(connection, "connection");
            }
            if (networkConnection == null) {
                warnChannelFailure(player, "network connection");
                return null;
            }

            Object channel = getFieldByTypeName(networkConnection, Channel.class.getName());
            if (!(channel instanceof Channel)) {
                channel = getFieldValue(networkConnection, "channel");
            }
            if (!(channel instanceof Channel)) {
                warnChannelFailure(player, "Netty channel");
                return null;
            }
            return (Channel) channel;
        } catch (Exception exception) {
            plugin.getLogger().warning("获取玩家 " + player.getName() + " 的 Channel 失败: "
                + exception.getClass().getSimpleName() + ": " + exception.getMessage());
            return null;
        }
    }

    public void injectPlayer(Player player) {
        UUID uuid = player.getUniqueId();
        if (!player.isOnline() || player.hasPermission("bandwidthlimiter.bypass")) {
            removePlayer(player);
            return;
        }

        Channel channel = getPlayerChannel(player);
        if (channel == null || !channel.isOpen()) {
            return;
        }
        channels.put(uuid, channel);
        if (injectionPending.putIfAbsent(uuid, Boolean.TRUE) != null) {
            return;
        }
        long limitBps = toBytesPerSecond(getPlayerLimit(player));

        channel.eventLoop().execute(() -> {
            try {
                if (!channel.isOpen()) {
                    return;
                }
                ChannelPipeline pipeline = channel.pipeline();
                Object existing = pipeline.get(HANDLER_NAME);
                if (existing instanceof PlayerBandwidthHandler) {
                    handlers.put(uuid, (PlayerBandwidthHandler) existing);
                    return;
                }
                if (existing != null) {
                    pipeline.remove(HANDLER_NAME);
                }

                String anchor = findOutboundAnchor(pipeline);
                if (anchor == null) {
                    plugin.getLogger().warning("玩家 " + player.getName()
                        + " 的出站编码器尚未就绪，稍后重试");
                    return;
                }

                // Outbound events travel tail -> head. Before the encoder means
                // the shaper receives the encoded ByteBuf, not the raw Packet.
                PlayerBandwidthHandler handler = new PlayerBandwidthHandler(
                    uuid, 0L, limitBps, CHECK_INTERVAL_MILLIS);
                pipeline.addBefore(anchor, HANDLER_NAME, handler);
                handlers.put(uuid, handler);
                plugin.getLogger().info("已为玩家 " + player.getName()
                    + " 注入带宽限制: " + getPlayerLimit(player) + " KB/s");
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("注入玩家 " + player.getName()
                    + " 的带宽处理器失败: " + exception.getMessage());
            } finally {
                injectionPending.remove(uuid);
            }
        });
    }

    public void removePlayer(Player player) {
        UUID uuid = player.getUniqueId();
        injectionPending.remove(uuid);
        PlayerBandwidthHandler handler = handlers.remove(uuid);
        Channel channel = channels.remove(uuid);
        if (handler == null || channel == null || !channel.isOpen()) {
            return;
        }
        channel.eventLoop().execute(() -> {
            try {
                if (channel.pipeline().get(HANDLER_NAME) != null) {
                    channel.pipeline().remove(HANDLER_NAME);
                }
            } catch (RuntimeException ignored) {
                // The channel may be closing concurrently.
            }
        });
    }

    public void updatePlayerLimit(Player player) {
        if (player.hasPermission("bandwidthlimiter.bypass")) {
            removePlayer(player);
            return;
        }
        PlayerBandwidthHandler handler = handlers.get(player.getUniqueId());
        Channel channel = channels.get(player.getUniqueId());
        if (handler == null || channel == null || !channel.isOpen()) {
            injectPlayer(player);
            return;
        }
        long limitBps = toBytesPerSecond(getPlayerLimit(player));
        channel.eventLoop().execute(() -> handler.setWriteLimit(limitBps));
    }

    public void setPlayerLimit(UUID uuid, long limitKBps) {
        if (limitKBps <= 0) {
            throw new IllegalArgumentException("带宽限制必须大于 0");
        }
        playerLimits.put(uuid, limitKBps);
        plugin.getConfig().set("player-limits." + uuid, limitKBps);
        plugin.saveConfig();
        Player player = Bukkit.getPlayer(uuid);
        if (player != null && player.isOnline()) {
            updatePlayerLimit(player);
        }
    }

    public void removePlayerLimit(UUID uuid) {
        playerLimits.remove(uuid);
        plugin.getConfig().set("player-limits." + uuid, null);
        plugin.saveConfig();
        Player player = Bukkit.getPlayer(uuid);
        if (player != null && player.isOnline()) {
            updatePlayerLimit(player);
        }
    }

    public long getPlayerLimit(Player player) {
        return playerLimits.getOrDefault(player.getUniqueId(), defaultLimitKBps);
    }

    public long getPlayerCurrentRate(Player player) {
        PlayerBandwidthHandler handler = handlers.get(player.getUniqueId());
        return handler == null ? -1L : handler.getCurrentWriteRate();
    }

    public long getDefaultLimit() {
        return defaultLimitKBps;
    }

    public void setDefaultLimit(long limitKBps) {
        if (limitKBps <= 0) {
            throw new IllegalArgumentException("带宽限制必须大于 0");
        }
        defaultLimitKBps = limitKBps;
        plugin.getConfig().set("default-limit-kbps", limitKBps);
        plugin.saveConfig();
    }

    public void removeAllHandlers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            removePlayer(player);
        }
        injectionPending.clear();
        handlers.clear();
        channels.clear();
    }

    public boolean hasHandler(Player player) {
        return handlers.containsKey(player.getUniqueId());
    }

    private String findOutboundAnchor(ChannelPipeline pipeline) {
        if (pipeline.get("encoder") != null) {
            return "encoder";
        }
        if (pipeline.get("outbound_config") != null) {
            return "outbound_config";
        }
        return null;
    }

    private long toBytesPerSecond(long limitKBps) {
        try {
            return Math.multiplyExact(limitKBps, 1024L);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private long positiveLimit(long value, long fallback) {
        return value > 0 ? value : fallback;
    }

    private void warnChannelFailure(Player player, String part) {
        plugin.getLogger().warning("无法获取玩家 " + player.getName() + " 的 " + part);
    }

    private Object invokeNoArg(Object object, String methodName) throws ReflectiveOperationException {
        Method method = object.getClass().getMethod(methodName);
        return method.invoke(object);
    }

    private Object getFieldValue(Object object, String fieldName) {
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(object);
            } catch (NoSuchFieldException ignored) {
                // Search the superclass.
            } catch (ReflectiveOperationException | RuntimeException exception) {
                return null;
            }
        }
        return null;
    }

    private Object getFieldByTypeName(Object object, String typeName) {
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (isAssignableToName(field.getType(), typeName)) {
                    try {
                        field.setAccessible(true);
                        return field.get(object);
                    } catch (ReflectiveOperationException | RuntimeException ignored) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    private boolean isAssignableToName(Class<?> type, String targetName) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if (current.getName().equals(targetName)) {
                return true;
            }
            for (Class<?> iface : current.getInterfaces()) {
                if (iface.getName().equals(targetName)) {
                    return true;
                }
            }
        }
        return false;
    }
}
