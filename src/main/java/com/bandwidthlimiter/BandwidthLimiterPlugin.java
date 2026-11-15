package com.bandwidthlimiter;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.logging.Level;

/** BandwidthLimiter plugin entry point for Paper/Folia 26.3. */
public final class BandwidthLimiterPlugin extends JavaPlugin {

    private static BandwidthLimiterPlugin instance;
    private BandwidthManager bandwidthManager;
    private volatile SqliteUsageStore usageStore;
    private boolean folia;

    @Override
    public void onEnable() {
        instance = this;
        detectFolia();
        saveDefaultConfig();

        bandwidthManager = new BandwidthManager(this);
        try {
            usageStore = new SqliteUsageStore(getDataFolder().toPath().resolve("usage.db"),
                bandwidthManager.getUsageTracker(),
                exception -> getLogger().log(Level.SEVERE, "SQLite 流量保存失败，将在后续保存时重试", exception));
        } catch (IOException exception) {
            getLogger().log(Level.SEVERE, "无法打开流量数据库，插件停止启用以保护已有数据", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getLogger().info("SQLite 流量统计已恢复，每 " + SqliteUsageStore.SAVE_INTERVAL_SECONDS
            + " 秒异步保存至 usage.db");
        bandwidthManager.loadConfig();

        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        BandwidthCommand command = new BandwidthCommand(this);
        if (getCommand("bandwidthlimiter") != null) {
            getCommand("bandwidthlimiter").setExecutor(command);
            getCommand("bandwidthlimiter").setTabCompleter(command);
        }

        getLogger().info("BandwidthLimiter v" + getDescription().getVersion()
            + " enabled on " + (folia ? "Folia" : "Paper/Spigot")
            + ", default limit: " + bandwidthManager.getDefaultLimit() + " KB/s");
    }

    @Override
    public void onDisable() {
        try {
            if (bandwidthManager != null) {
                bandwidthManager.removeAllHandlers();
            }
        } finally {
            if (usageStore != null) {
                try {
                    usageStore.close();
                } catch (IOException exception) {
                    getLogger().log(Level.SEVERE, "停服时流量数据库保存失败", exception);
                }
                usageStore = null;
            }
        }
        if (instance == this) {
            instance = null;
        }
    }

    void saveUsageAsync() {
        SqliteUsageStore store = usageStore;
        if (store != null) {
            store.flushAsync();
        }
    }

    private void detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            folia = true;
            getLogger().info("Detected Folia; using region-safe scheduling");
        } catch (ClassNotFoundException ignored) {
            folia = false;
        }
    }

    public static BandwidthLimiterPlugin getInstance() {
        return instance;
    }

    public BandwidthManager getBandwidthManager() {
        return bandwidthManager;
    }

    public boolean isFolia() {
        return folia;
    }
}
