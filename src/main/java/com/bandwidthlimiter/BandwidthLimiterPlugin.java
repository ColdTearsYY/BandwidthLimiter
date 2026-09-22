package com.bandwidthlimiter;

import org.bukkit.plugin.java.JavaPlugin;

/** BandwidthLimiter plugin entry point for Paper/Folia 26.3. */
public final class BandwidthLimiterPlugin extends JavaPlugin {

    private static BandwidthLimiterPlugin instance;
    private BandwidthManager bandwidthManager;
    private boolean folia;

    @Override
    public void onEnable() {
        instance = this;
        detectFolia();
        saveDefaultConfig();

        bandwidthManager = new BandwidthManager(this);
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
        if (bandwidthManager != null) {
            bandwidthManager.removeAllHandlers();
        }
        if (instance == this) {
            instance = null;
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
