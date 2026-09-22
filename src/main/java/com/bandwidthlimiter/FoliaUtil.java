package com.bandwidthlimiter;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.function.Consumer;

/** Small scheduler bridge for Paper/Spigot and Folia. */
public final class FoliaUtil {

    private FoliaUtil() {
    }

    public static void runTaskLater(BandwidthLimiterPlugin plugin, Player player,
                                    Runnable task, long delayTicks) {
        if (!plugin.isFolia()) {
            Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
            return;
        }
        try {
            Object scheduler = player.getClass().getMethod("getScheduler").invoke(player);
            Method runDelayed = scheduler.getClass().getMethod(
                "runDelayed", org.bukkit.plugin.Plugin.class, Consumer.class,
                Runnable.class, long.class);
            Consumer<Object> callback = ignored -> task.run();
            runDelayed.invoke(scheduler, plugin, callback, null, delayTicks);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            plugin.getLogger().warning("Folia 玩家调度失败: " + exception.getMessage());
        }
    }

    public static void runTask(BandwidthLimiterPlugin plugin, Runnable task) {
        if (!plugin.isFolia()) {
            Bukkit.getScheduler().runTask(plugin, task);
            return;
        }
        try {
            Object scheduler = Bukkit.class.getMethod("getGlobalRegionScheduler").invoke(null);
            Method execute = scheduler.getClass().getMethod(
                "execute", org.bukkit.plugin.Plugin.class, Runnable.class);
            execute.invoke(scheduler, plugin, task);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            plugin.getLogger().warning("Folia 全局调度失败: " + exception.getMessage());
        }
    }

    public static void runAsync(BandwidthLimiterPlugin plugin, Runnable task) {
        if (!plugin.isFolia()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
            return;
        }
        try {
            Object scheduler = Bukkit.class.getMethod("getAsyncScheduler").invoke(null);
            Method runNow = scheduler.getClass().getMethod(
                "runNow", org.bukkit.plugin.Plugin.class, Consumer.class);
            Consumer<Object> callback = ignored -> task.run();
            runNow.invoke(scheduler, plugin, callback);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            plugin.getLogger().warning("Folia 异步调度失败: " + exception.getMessage());
        }
    }
}
