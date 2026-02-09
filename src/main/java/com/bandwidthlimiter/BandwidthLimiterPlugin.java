package com.bandwidthlimiter;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * BandwidthLimiter - 控制每个玩家的带宽上限
 * 兼容 Folia 1.21.1 / Paper / Spigot
 */
public class BandwidthLimiterPlugin extends JavaPlugin {

    private static BandwidthLimiterPlugin instance;
    private BandwidthManager bandwidthManager;
    private boolean isFolia = false;

    @Override
    public void onEnable() {
        instance = this;

        // 检测是否运行在 Folia 上
        detectFolia();

        // 保存默认配置
        saveDefaultConfig();

        // 初始化带宽管理器
        bandwidthManager = new BandwidthManager(this);
        bandwidthManager.loadConfig();

        // 注册事件监听器
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);

        // 注册命令
        BandwidthCommand cmdExecutor = new BandwidthCommand(this);
        getCommand("bandwidthlimiter").setExecutor(cmdExecutor);
        getCommand("bandwidthlimiter").setTabCompleter(cmdExecutor);

        getLogger().info("========================================");
        getLogger().info(" BandwidthLimiter v" + getDescription().getVersion());
        getLogger().info(" 运行环境: " + (isFolia ? "Folia" : "Paper/Spigot"));
        getLogger().info(" 默认带宽限制: " + bandwidthManager.getDefaultLimit() + " KB/s");
        getLogger().info("========================================");
    }

    @Override
    public void onDisable() {
        // 清理所有玩家的带宽处理器
        if (bandwidthManager != null) {
            bandwidthManager.removeAllHandlers();
        }
        getLogger().info("BandwidthLimiter 已禁用");
    }

    /**
     * 检测当前服务器是否为 Folia
     */
    private void detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            isFolia = true;
            getLogger().info("检测到 Folia 环境，启用区域化调度");
        } catch (ClassNotFoundException e) {
            isFolia = false;
        }
    }

    public static BandwidthLimiterPlugin getInstance() {
        return instance;
    }

    public BandwidthManager getBandwidthManager() {
        return bandwidthManager;
    }

    public boolean isFolia() {
        return isFolia;
    }
}