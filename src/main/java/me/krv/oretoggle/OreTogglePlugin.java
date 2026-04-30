package me.krv.oretoggle;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.List;

public final class OreTogglePlugin extends JavaPlugin {

    private StateStorage storage;
    private OreCacheManager cacheManager;
    private OreService oreService;
    private OreCommand oreCommand;
    private ConfigSettings settings;
    private BukkitTask chunkProcessorTask;
    private BukkitTask playerScanTask;
    private BukkitTask autosaveTask;

    @Override
    public void onEnable() {
        getLogger().info("Enabling OreToggle.");
        saveDefaultConfig();
        reloadConfig();

        settings = ConfigSettings.from(getConfig());
        cacheManager = new OreCacheManager();
        storage = new StateStorage(new File(getDataFolder(), "ore-state.yml"), getLogger());
        OreDefinitions definitions = new OreDefinitions();
        cacheManager.load(storage.load(definitions.keys()));
        logDisabledOres();

        ChunkQueue chunkQueue = new ChunkQueue(settings.maxQueueSize());
        OreScanner scanner = new OreScanner(cacheManager, settings, getLogger());
        oreService = new OreService(this, definitions, cacheManager, chunkQueue, scanner, storage, settings);
        oreCommand = new OreCommand(definitions, oreService);

        getCommand("toggleore").setExecutor(oreCommand);
        getCommand("toggleore").setTabCompleter(oreCommand);
        getCommand("restoreore").setExecutor(oreCommand);
        getCommand("restoreore").setTabCompleter(oreCommand);

        startPlayerScanTask();
        oreService.enqueueChunksAroundPlayersForDisabledOres(1L);
        if (!cacheManager.disabledOreKeys().isEmpty()) {
            ensureChunkProcessorRunning();
        }
        startAutosaveTask();
        getLogger().info("OreToggle enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info("Disabling OreToggle.");
        stopTask(chunkProcessorTask);
        stopTask(playerScanTask);
        stopTask(autosaveTask);
        chunkProcessorTask = null;
        playerScanTask = null;
        autosaveTask = null;

        if (oreService != null) {
            oreService.cancelActiveJob();
            storage.save(cacheManager.snapshot());
        }
        getLogger().info("OreToggle disabled.");
    }

    void ensureChunkProcessorRunning() {
        if (chunkProcessorTask != null && !chunkProcessorTask.isCancelled()) {
            return;
        }
        chunkProcessorTask = Bukkit.getScheduler().runTaskTimer(this, oreService::processPendingChunks, 1L, 1L);
    }

    void stopChunkProcessor() {
        stopTask(chunkProcessorTask);
        chunkProcessorTask = null;
    }

    private void startPlayerScanTask() {
        playerScanTask = Bukkit.getScheduler().runTaskTimer(
                this,
                () -> {
                    if (oreService.scanChunksAroundPlayers()) {
                        ensureChunkProcessorRunning();
                    }
                },
                settings.playerScanIntervalTicks(),
                settings.playerScanIntervalTicks()
        );
    }

    private void startAutosaveTask() {
        long intervalSeconds = settings.autosaveIntervalSeconds();
        if (intervalSeconds <= 0) {
            getLogger().info("Autosave disabled.");
            return;
        }

        long intervalTicks = intervalSeconds * 20L;
        autosaveTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (settings.debug()) {
                getLogger().info("Autosaving ore cache.");
            }
            storage.save(cacheManager.snapshot());
        }, intervalTicks, intervalTicks);
        getLogger().info("Autosave enabled every " + intervalSeconds + " seconds.");
    }

    private void logDisabledOres() {
        List<String> disabledOreKeys = cacheManager.disabledOreKeys();
        if (disabledOreKeys.isEmpty()) {
            getLogger().info("Loaded disabled ores: none.");
            return;
        }
        getLogger().info("Loaded disabled ores: " + String.join(", ", disabledOreKeys) + ".");
    }

    private void stopTask(BukkitTask task) {
        if (task != null && !task.isCancelled()) {
            task.cancel();
        }
    }
}
