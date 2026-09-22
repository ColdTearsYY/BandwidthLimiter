package com.bandwidthlimiter;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Handles player channel lifecycle. */
public final class PlayerListener implements Listener {

    private final BandwidthLimiterPlugin plugin;

    public PlayerListener(BandwidthLimiterPlugin plugin) {
        this.plugin = plugin;
    }

    /** The channel may be created shortly after PlayerJoinEvent, so retry. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        scheduleInjection(event.getPlayer(), 0);
    }

    private void scheduleInjection(Player player, int attempt) {
        FoliaUtil.runTaskLater(plugin, player, () -> {
            if (!player.isOnline()) {
                return;
            }
            plugin.getBandwidthManager().injectPlayer(player);
            if (!plugin.getBandwidthManager().hasHandler(player) && attempt < 5) {
                scheduleInjection(player, attempt + 1);
            }
        }, attempt == 0 ? 20L : 10L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        plugin.getBandwidthManager().removePlayer(event.getPlayer());
    }
}
