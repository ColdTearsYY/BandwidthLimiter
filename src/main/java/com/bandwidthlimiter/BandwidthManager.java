package com.bandwidthlimiter;

import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 带宽管理器 - 负责管理所有玩家的带宽限制
 * 通过反射获取玩家的 Netty Channel，注入自定义的流量整形处理器
 */
public class BandwidthManager {

    private final BandwidthLimiterPlugin plugin;
    private final Map<UUID, PlayerBandwidthHandler> handlers = new ConcurrentHashMap<>();
    private final Map<UUID, Long> playerLimits = new ConcurrentHashMap<>();

    private long defaultLimitKBps = 512; // 默认 512 KB/s
    private static final String HANDLER_NAME = "bandwidth_limiter";

    public BandwidthManager(BandwidthLimiterPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 从配置文件加载设置
     */
    public void loadConfig() {
        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();

        defaultLimitKBps = config.getLong("default-limit-kbps", 512);

        // 加载每个玩家的独立限制
        playerLimits.clear();
        if (config.isConfigurationSection("player-limits")) {
            for (String key : config.getConfigurationSection("player-limits").getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(key);
                    long limit = config.getLong("player-limits." + key);
                    playerLimits.put(uuid, limit);
                } catch (IllegalArgumentException e) {
                    // 尝试通过玩家名查找
                    Player p = Bukkit.getPlayerExact(key);
                    if (p != null) {
                        long limit = config.getLong("player-limits." + key);
                        playerLimits.put(p.getUniqueId(), limit);
                    }
                }
            }
        }

        // 更新所有已在线玩家的限制
        for (Player player : Bukkit.getOnlinePlayers()) {
            updatePlayerLimit(player);
        }

        plugin.getLogger().info("配置已重新加载 - 默认限制: " + defaultLimitKBps + " KB/s");
    }

    /**
     * 通过反射获取玩家的 Netty Channel
     * 兼容 1.21.1 (Paper/Folia)
     */
    public Channel getPlayerChannel(Player player) {
        try {
            // 获取 CraftPlayer -> getHandle() -> ServerPlayer
            Method getHandle = player.getClass().getMethod("getHandle");
            Object serverPlayer = getHandle.invoke(player);

            // ServerPlayer -> connection (ServerGamePacketListenerImpl)
            Object connection = getFieldValue(serverPlayer, "connection");
            if (connection == null) {
                // 尝试不同的字段名 (不同版本映射可能不同)
                connection = getFieldByType(serverPlayer,
                    "net.minecraft.server.network.ServerGamePacketListenerImpl");
            }

            if (connection == null) {
                plugin.getLogger().warning("无法获取玩家 " + player.getName() + " 的连接对象");
                return null;
            }

            // ServerGamePacketListenerImpl -> connection (Connection)
            // 在 1.21.1 中字段名为 "connection" 或通过类型查找
            Object networkManager = getFieldByType(connection,
                "net.minecraft.network.Connection");

            if (networkManager == null) {
                plugin.getLogger().warning("无法获取玩家 " + player.getName() + " 的网络管理器");
                return null;
            }

            // Connection -> channel (io.netty.channel.Channel)
            Object channel = getFieldByType(networkManager, "io.netty.channel.Channel");
            if (channel == null) {
                // 直接查找名为 "channel" 的字段
                channel = getFieldValue(networkManager, "channel");
            }

            return (Channel) channel;

        } catch (Exception e) {
            plugin.getLogger().severe("获取玩家 " + player.getName() + " 的 Channel 失败: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 为玩家注入带宽限制处理器
     */
    public void injectPlayer(Player player) {
        if (player.hasPermission("bandwidthlimiter.bypass")) {
            plugin.getLogger().info("玩家 " + player.getName() + " 拥有绕过权限，跳过注入");
            return;
        }

        Channel channel = getPlayerChannel(player);
        if (channel == null) {
            return;
        }

        long limitKBps = getPlayerLimit(player);
        long limitBps = limitKBps * 1024; // 转换为 Bytes/s

        // 在 Channel 的 EventLoop 中操作，确保线程安全
        channel.eventLoop().execute(() -> {
            try {
                ChannelPipeline pipeline = channel.pipeline();

                // 如果已存在，先移除
                if (pipeline.get(HANDLER_NAME) != null) {
                    pipeline.remove(HANDLER_NAME);
                }

                // 创建并添加带宽限制处理器
                // writeLimit: 出站限制 (服务器->客户端)
                // readLimit: 入站限制 (客户端->服务器), 0 = 不限制
                PlayerBandwidthHandler handler = new PlayerBandwidthHandler(
                    player.getUniqueId(),
                    0,          // 不限制入站 (客户端->服务器)
                    limitBps,   // 限制出站 (服务器->客户端)
                    1000        // 检查间隔 1 秒
                );

                // 在 encoder 之后添加，这样可以限制编码后的实际字节流
                if (pipeline.get("encoder") != null) {
                    pipeline.addAfter("encoder", HANDLER_NAME, handler);
                } else {
                    // 找不到 encoder，添加到最前面
                    pipeline.addFirst(HANDLER_NAME, handler);
                }

                handlers.put(player.getUniqueId(), handler);

                plugin.getLogger().info("已为玩家 " + player.getName()
                    + " 注入带宽限制: " + limitKBps + " KB/s");

            } catch (Exception e) {
                plugin.getLogger().severe("注入玩家 " + player.getName()
                    + " 的带宽限制处理器失败: " + e.getMessage());
            }
        });
    }

    /**
     * 移除玩家的带宽限制处理器
     */
    public void removePlayer(Player player) {
        PlayerBandwidthHandler handler = handlers.remove(player.getUniqueId());
        if (handler == null) return;

        Channel channel = getPlayerChannel(player);
        if (channel == null) return;

        channel.eventLoop().execute(() -> {
            try {
                ChannelPipeline pipeline = channel.pipeline();
                if (pipeline.get(HANDLER_NAME) != null) {
                    pipeline.remove(HANDLER_NAME);
                    plugin.getLogger().info("已移除玩家 " + player.getName() + " 的带宽限制");
                }
            } catch (Exception e) {
                // 玩家可能已断开，忽略错误
            }
        });
    }

    /**
     * 更新玩家的带宽限制
     */
    public void updatePlayerLimit(Player player) {
        PlayerBandwidthHandler handler = handlers.get(player.getUniqueId());
        if (handler != null) {
            long limitKBps = getPlayerLimit(player);
            long limitBps = limitKBps * 1024;
            handler.setWriteLimit(limitBps);
            handler.setReadLimit(0);
            plugin.getLogger().info("已更新玩家 " + player.getName()
                + " 的带宽限制为: " + limitKBps + " KB/s");
        } else {
            // 如果处理器不存在，尝试重新注入
            injectPlayer(player);
        }
    }

    /**
     * 设置特定玩家的带宽限制 (KB/s)
     */
    public void setPlayerLimit(UUID uuid, long limitKBps) {
        playerLimits.put(uuid, limitKBps);

        // 保存到配置
        plugin.getConfig().set("player-limits." + uuid.toString(), limitKBps);
        plugin.saveConfig();

        // 如果玩家在线，立即更新
        Player player = Bukkit.getPlayer(uuid);
        if (player != null && player.isOnline()) {
            updatePlayerLimit(player);
        }
    }

    /**
     * 移除特定玩家的独立限制 (恢复使用默认值)
     */
    public void removePlayerLimit(UUID uuid) {
        playerLimits.remove(uuid);
        plugin.getConfig().set("player-limits." + uuid.toString(), null);
        plugin.saveConfig();

        Player player = Bukkit.getPlayer(uuid);
        if (player != null && player.isOnline()) {
            updatePlayerLimit(player);
        }
    }

    /**
     * 获取玩家的带宽限制 (KB/s)
     */
    public long getPlayerLimit(Player player) {
        return playerLimits.getOrDefault(player.getUniqueId(), defaultLimitKBps);
    }

    /**
     * 获取玩家的当前出站速率 (bytes/s)
     */
    public long getPlayerCurrentRate(Player player) {
        PlayerBandwidthHandler handler = handlers.get(player.getUniqueId());
        if (handler != null) {
            return handler.trafficCounter().lastWrittenBytes();
        }
        return -1;
    }

    /**
     * 获取默认限制
     */
    public long getDefaultLimit() {
        return defaultLimitKBps;
    }

    /**
     * 设置默认限制
     */
    public void setDefaultLimit(long limitKBps) {
        this.defaultLimitKBps = limitKBps;
        plugin.getConfig().set("default-limit-kbps", limitKBps);
        plugin.saveConfig();
    }

    /**
     * 移除所有处理器
     */
    public void removeAllHandlers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            removePlayer(player);
        }
        handlers.clear();
    }

    /**
     * 检查玩家是否有带宽处理器
     */
    public boolean hasHandler(Player player) {
        return handlers.containsKey(player.getUniqueId());
    }

    // === 反射工具方法 ===

    private Object getFieldValue(Object obj, String fieldName) {
        try {
            Class<?> clazz = obj.getClass();
            while (clazz != null) {
                try {
                    Field field = clazz.getDeclaredField(fieldName);
                    field.setAccessible(true);
                    return field.get(obj);
                } catch (NoSuchFieldException e) {
                    clazz = clazz.getSuperclass();
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    private Object getFieldByType(Object obj, String typeName) {
        try {
            Class<?> clazz = obj.getClass();
            while (clazz != null) {
                for (Field field : clazz.getDeclaredFields()) {
                    if (isAssignableFrom(field.getType(), typeName)) {
                        field.setAccessible(true);
                        return field.get(obj);
                    }
                }
                clazz = clazz.getSuperclass();
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    private boolean isAssignableFrom(Class<?> type, String targetName) {
        Class<?> current = type;
        while (current != null) {
            if (current.getName().equals(targetName)) return true;
            for (Class<?> iface : current.getInterfaces()) {
                if (iface.getName().equals(targetName)) return true;
            }
            current = current.getSuperclass();
        }
        return false;
    }
}