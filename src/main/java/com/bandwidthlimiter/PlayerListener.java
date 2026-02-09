package com.bandwidthlimiter;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * 玩家事件监听器
 * 在玩家加入时注入带宽限制处理器，离开时清理
 */
public class PlayerListener implements Listener {

    private final BandwidthLimiterPlugin plugin;

    public PlayerListener(BandwidthLimiterPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 玩家加入时注入带宽限制
     * 使用 MONITOR 优先级确保在其他插件处理完后执行
     * 延迟 1 tick 执行，确保玩家的 Channel 已完全初始化
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // 延迟注入，确保连接完全建立
        FoliaUtil.runTaskLater(plugin, player, () -> {
            plugin.getBandwidthManager().injectPlayer(player);
        }, 20L); // 延迟 1 秒 (20 ticks)
    }

    /**
     * 玩家离开时清理
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        plugin.getBandwidthManager().removePlayer(player);
    }
}