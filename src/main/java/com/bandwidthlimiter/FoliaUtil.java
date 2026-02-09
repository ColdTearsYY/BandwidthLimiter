package com.bandwidthlimiter;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Folia 兼容工具类
 *
 * Folia 使用区域化多线程，不能使用传统的 BukkitScheduler。
 * 此工具类自动检测运行环境，使用对应的调度器:
 * - Folia: 使用 RegionScheduler / EntityScheduler
 * - Paper/Spigot: 使用传统 BukkitScheduler
 */
public class FoliaUtil {

    private FoliaUtil() {}

    /**
     * 在玩家所在区域延迟执行任务
     * Folia: 使用 player.getScheduler().runDelayed()
     * Paper: 使用 Bukkit.getScheduler().runTaskLater()
     */
    public static void runTaskLater(BandwidthLimiterPlugin plugin, Player player,
                                     Runnable task, long delayTicks) {
        if (plugin.isFolia()) {
            try {
                // Folia: player.getScheduler().runDelayed(plugin, task, null, delayTicks)
                Object scheduler = player.getClass().getMethod("getScheduler").invoke(player);
                scheduler.getClass().getMethod("runDelayed",
                    org.bukkit.plugin.Plugin.class,
                    java.util.function.Consumer.class,
                    Runnable.class,
                    long.class
                ).invoke(scheduler, plugin,
                    (java.util.function.Consumer) (scheduledTask) -> task.run(),
                    null, delayTicks);
            } catch (Exception e) {
                // 回退到 Bukkit 调度器
                plugin.getLogger().warning("Folia 调度失败，回退到 Bukkit: " + e.getMessage());
                Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
            }
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
        }
    }

    /**
     * 在全局区域执行任务
     * Folia: 使用 Bukkit.getGlobalRegionScheduler()
     * Paper: 使用 Bukkit.getScheduler().runTask()
     */
    public static void runTask(BandwidthLimiterPlugin plugin, Runnable task) {
        if (plugin.isFolia()) {
            try {
                Object globalScheduler = Bukkit.class.getMethod("getGlobalRegionScheduler")
                    .invoke(null);
                globalScheduler.getClass().getMethod("execute",
                    org.bukkit.plugin.Plugin.class, Runnable.class
                ).invoke(globalScheduler, plugin, task);
            } catch (Exception e) {
                plugin.getLogger().warning("Folia 全局调度失败: " + e.getMessage());
                Bukkit.getScheduler().runTask(plugin, task);
            }
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    /**
     * 在异步线程执行任务
     * Folia: 使用 Bukkit.getAsyncScheduler()
     * Paper: 使用 Bukkit.getScheduler().runTaskAsynchronously()
     */
    public static void runAsync(BandwidthLimiterPlugin plugin, Runnable task) {
        if (plugin.isFolia()) {
            try {
                Object asyncScheduler = Bukkit.class.getMethod("getAsyncScheduler")
                    .invoke(null);
                asyncScheduler.getClass().getMethod("runNow",
                    org.bukkit.plugin.Plugin.class,
                    java.util.function.Consumer.class
                ).invoke(asyncScheduler, plugin,
                    (java.util.function.Consumer) (scheduledTask) -> task.run());
            } catch (Exception e) {
                plugin.getLogger().warning("Folia 异步调度失败: " + e.getMessage());
                Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
            }
        } else {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        }
    }
}
