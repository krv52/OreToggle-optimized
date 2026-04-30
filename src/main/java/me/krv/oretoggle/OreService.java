package me.krv.oretoggle;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

final class OreService {
    private final OreTogglePlugin plugin;
    private final OreDefinitions definitions;
    private final OreCacheManager cacheManager;
    private final ChunkQueue chunkQueue;
    private final OreScanner scanner;
    private final StateStorage storage;
    private final ConfigSettings settings;
    private BukkitTask activeJob;
    private ProcessingChunk activeProcessingChunk;
    private String activeJobDescription;
    private long schedulerTick;
    private boolean saveQueued;
    private boolean lowTpsWarningLogged;

    OreService(
            OreTogglePlugin plugin,
            OreDefinitions definitions,
            OreCacheManager cacheManager,
            ChunkQueue chunkQueue,
            OreScanner scanner,
            StateStorage storage,
            ConfigSettings settings
    ) {
        this.plugin = plugin;
        this.definitions = definitions;
        this.cacheManager = cacheManager;
        this.chunkQueue = chunkQueue;
        this.scanner = scanner;
        this.storage = storage;
        this.settings = settings;
    }

    void enableOre(OreDefinition definition) {
        cacheManager.setState(definition.key(), OreRuntimeState.ENABLED);
        removePendingChunksForOre(definition.key());
        persistState();
    }

    int disableOre(OreDefinition definition) {
        cacheManager.setState(definition.key(), OreRuntimeState.DISABLED);
        int queued = enqueueChunksAroundPlayersForOre(definition.key(), 1L);
        plugin.ensureChunkProcessorRunning();
        persistState();
        return queued;
    }

    void restoreOre(CommandSender sender, OreDefinition definition) {
        Deque<RestoreTarget> queue = cacheManager.collectRestoreTargets(definition.key());
        if (queue.isEmpty()) {
            cacheManager.setState(definition.key(), OreRuntimeState.ENABLED);
            removePendingChunksForOre(definition.key());
            cacheManager.clearProcessed(definition.key());
            persistState();
            sender.sendMessage("No saved blocks found for " + definition.displayName() + ".");
            return;
        }
        startRestoreJob(sender, definition, queue);
    }

    void enqueueChunksAroundPlayersForDisabledOres(long delayTicks) {
        for (OreDefinition definition : definitions.all()) {
            if (cacheManager.state(definition.key()) == OreRuntimeState.DISABLED) {
                enqueueChunksAroundPlayersForOre(definition.key(), delayTicks);
            }
        }
    }

    boolean scanChunksAroundPlayers() {
        boolean queued = false;
        for (OreDefinition definition : definitions.all()) {
            if (cacheManager.state(definition.key()) == OreRuntimeState.DISABLED) {
                queued |= enqueueChunksAroundPlayersForOre(definition.key(), 1L) > 0;
            }
        }
        return queued;
    }

    void processPendingChunks() {
        schedulerTick++;

        if (chunkQueue.isEmpty() && activeProcessingChunk == null) {
            plugin.stopChunkProcessor();
            lowTpsWarningLogged = false;
            return;
        }

        if (hasActiveJob()) {
            return;
        }

        boolean underLoad = isServerUnderLoad();
        Double currentTps = currentTps();
        Budget budget = budgetFor(currentTps);
        int allowedChunksThisTick = budget.chunksPerTick();
        int blockBudget = budget.blockChecksPerTick();

        if (underLoad && !lowTpsWarningLogged) {
            plugin.getLogger().info("Ore chunk processing slowed because TPS is below " + settings.lowTpsThreshold() + ".");
            lowTpsWarningLogged = true;
        } else if (!underLoad) {
            lowTpsWarningLogged = false;
        }

        int processedCount = 0;
        int blockChecks = 0;
        boolean persistNeeded = false;

        while (blockChecks < blockBudget && processedCount < allowedChunksThisTick) {
            if (activeProcessingChunk == null) {
                PendingChunk pending = nextPendingChunk();
                if (pending == null) {
                    break;
                }

                OreDefinition definition = definitions.get(pending.oreKey());
                World world = Bukkit.getWorld(pending.worldName());
                if (definition == null || world == null || !world.isChunkLoaded(pending.chunkCoord().x(), pending.chunkCoord().z())) {
                    continue;
                }

                int currentDistance = nearestPlayerDistanceSquared(pending.worldName(), pending.chunkCoord());
                if (currentDistance < 0) {
                    continue;
                }

                activeProcessingChunk = new ProcessingChunk(
                        pending.oreKey(),
                        pending.worldName(),
                        pending.chunkCoord(),
                        definition,
                        world.getMinHeight()
                );
            }

            ChunkScanResult result = scanner.continueScan(activeProcessingChunk, blockBudget - blockChecks);
            blockChecks += result.blockChecks();

            if (result.finished()) {
                if (result.completed()) {
                    cacheManager.markProcessed(activeProcessingChunk.oreKey(), activeProcessingChunk.worldName(), activeProcessingChunk.chunkCoord());
                    persistNeeded = true;
                    processedCount++;
                }
                activeProcessingChunk = null;
            } else {
                break;
            }
        }

        if (settings.debug() && blockChecks > 0) {
            plugin.getLogger().info("Ore scan tick: checks=" + blockChecks
                    + ", active=" + (activeProcessingChunk != null)
                    + ", pending=" + chunkQueue.size() + ".");
        }

        if (persistNeeded) {
            persistState();
        }
    }

    boolean hasActiveJob() {
        return activeJob != null && !activeJob.isCancelled();
    }

    String activeJobDescription() {
        return activeJobDescription;
    }

    void cancelActiveJob() {
        if (activeJob != null && !activeJob.isCancelled()) {
            activeJob.cancel();
        }
        activeJob = null;
        activeJobDescription = null;
        activeProcessingChunk = null;
    }

    private int enqueueChunksAroundPlayersForOre(String oreKey, long delayTicks) {
        long readyTick = schedulerTick + Math.max(1L, delayTicks);
        int radius = settings.playerScanRadiusChunks();
        Map<ChunkRef, Integer> nearestDistances = new HashMap<>();

        for (Player player : Bukkit.getOnlinePlayers()) {
            World world = player.getWorld();
            int centerX = player.getLocation().getBlockX() >> 4;
            int centerZ = player.getLocation().getBlockZ() >> 4;

            for (int chunkX = centerX - radius; chunkX <= centerX + radius; chunkX++) {
                for (int chunkZ = centerZ - radius; chunkZ <= centerZ + radius; chunkZ++) {
                    if (!world.isChunkLoaded(chunkX, chunkZ)) {
                        continue;
                    }

                    ChunkCoord coord = new ChunkCoord(chunkX, chunkZ);
                    if (cacheManager.isProcessed(oreKey, world.getName(), coord)) {
                        continue;
                    }

                    int distanceSquared = square(chunkX - centerX) + square(chunkZ - centerZ);
                    ChunkRef chunkRef = new ChunkRef(world.getName(), chunkX, chunkZ);
                    nearestDistances.merge(chunkRef, distanceSquared, Math::min);
                }
            }
        }

        int queued = 0;
        for (Map.Entry<ChunkRef, Integer> entry : nearestDistances.entrySet()) {
            ChunkRef chunkRef = entry.getKey();
            if (chunkQueue.enqueue(oreKey, chunkRef.worldName(), chunkRef.coord(), readyTick, entry.getValue())) {
                queued++;
            }
        }

        if (chunkQueue.reachedCapWarning()) {
            plugin.getLogger().warning("Pending ore chunk queue reached cap of " + settings.maxQueueSize() + ". Oldest entries will be dropped.");
        }
        return queued;
    }

    private PendingChunk nextPendingChunk() {
        for (int i = 0; i < settings.maxPendingChunksPerTick(); i++) {
            PendingChunk pending = chunkQueue.pollReady(schedulerTick, settings.maxPendingChunksPerTick());
            if (pending == null) {
                return null;
            }
            if (cacheManager.state(pending.oreKey()) != OreRuntimeState.DISABLED) {
                continue;
            }
            if (cacheManager.isProcessed(pending.oreKey(), pending.worldName(), pending.chunkCoord())) {
                continue;
            }
            if (nearestPlayerDistanceSquared(pending.worldName(), pending.chunkCoord()) < 0) {
                continue;
            }
            return pending;
        }
        return null;
    }

    private void removePendingChunksForOre(String oreKey) {
        chunkQueue.removeOre(oreKey);
        if (activeProcessingChunk != null && activeProcessingChunk.oreKey().equals(oreKey)) {
            activeProcessingChunk = null;
        }
    }

    private void startRestoreJob(CommandSender sender, OreDefinition definition, Deque<RestoreTarget> queue) {
        int totalBlocks = queue.size();
        cacheManager.setState(definition.key(), OreRuntimeState.RESTORING);
        removePendingChunksForOre(definition.key());
        persistState();
        sender.sendMessage("Started restoring " + definition.displayName() + ". Queued " + totalBlocks + " blocks.");
        plugin.getLogger().info("Starting ore restore for " + definition.key() + " with " + totalBlocks + " saved entries.");
        activeJobDescription = "restoreore " + definition.key();

        activeJob = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            private int processedBlocksCount;
            private int restoredBlocksCount;
            private int ticksSinceProgress;
            private int lastReportedPercent = -1;

            @Override
            public void run() {
                if (queue.isEmpty()) {
                    cacheManager.setState(definition.key(), OreRuntimeState.ENABLED);
                    removePendingChunksForOre(definition.key());
                    cacheManager.clearProcessed(definition.key());
                    persistState();
                    clearActiveJob();
                    plugin.getLogger().info("Finished ore restore for " + definition.key() + ". Restored " + restoredBlocksCount + " blocks.");
                    sender.sendMessage(
                            "Restored " + restoredBlocksCount + " blocks for " + definition.displayName()
                                    + ". Processed " + processedBlocksCount + "/" + totalBlocks + " saved entries."
                    );
                    return;
                }

                int budget = restoreBudget();
                int batchCount = 0;
                int restoredThisTick = 0;
                while (batchCount < budget && !queue.isEmpty()) {
                    RestoreTarget target = queue.pollFirst();
                    if (target == null) {
                        continue;
                    }

                    if (restoreIfLoaded(target)) {
                        restoredBlocksCount++;
                        restoredThisTick++;
                    }

                    processedBlocksCount++;
                    batchCount++;
                }

                if (settings.debug() && restoredThisTick > 0) {
                    plugin.getLogger().info("Restored " + restoredThisTick + " " + definition.key() + " blocks this tick.");
                }

                ticksSinceProgress++;
                int currentPercent = totalBlocks == 0 ? 100 : (processedBlocksCount * 100) / totalBlocks;
                if (processedBlocksCount > 0 && ticksSinceProgress >= settings.progressMessageIntervalTicks() && currentPercent >= lastReportedPercent + 1) {
                    sendProgress(sender, "Restore progress for " + definition.displayName() + ": " + processedBlocksCount + "/" + totalBlocks + " entries.");
                    ticksSinceProgress = 0;
                    lastReportedPercent = currentPercent;
                }
            }
        }, 1L, 1L);
    }

    private boolean restoreIfLoaded(RestoreTarget target) {
        World world = Bukkit.getWorld(target.worldName());
        if (world == null || !world.isChunkLoaded(target.chunkCoord().x(), target.chunkCoord().z())) {
            return false;
        }

        SavedBlock savedBlock = target.savedBlock();
        Block block = world.getBlockAt(savedBlock.x(), savedBlock.y(), savedBlock.z());
        block.setType(savedBlock.material(), false);
        cacheManager.removeSavedBlock(target);
        return true;
    }

    private int restoreBudget() {
        return budgetFor(currentTps()).restoreBlocksPerTick();
    }

    private void persistState() {
        if (saveQueued) {
            return;
        }

        saveQueued = true;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            saveQueued = false;
            storage.save(cacheManager.snapshot());
        }, 20L);
    }

    private boolean isServerUnderLoad() {
        Double tps = currentTps();
        return tps != null && tps < settings.lowTpsThreshold();
    }

    private Budget budgetFor(Double tps) {
        if (tps == null || tps >= settings.lowTpsThreshold()) {
            return new Budget(settings.chunksPerTick(), settings.maxBlockChecksPerTick(), settings.restoreBlocksPerTick());
        }

        if (tps < settings.lowTpsThreshold() * 0.5D) {
            return new Budget(
                    1,
                    Math.max(256, settings.maxBlockChecksPerTick() / 4),
                    Math.max(1, settings.restoreBlocksPerTick() / 4)
            );
        }

        return new Budget(
                Math.max(1, Math.min(settings.chunksPerTick(), settings.minChunksPerTickUnderLoad())),
                Math.max(512, settings.maxBlockChecksPerTick() / 2),
                Math.max(1, settings.restoreBlocksPerTick() / 2)
        );
    }

    private int nearestPlayerDistanceSquared(String worldName, ChunkCoord chunkCoord) {
        int radiusSquared = square(settings.playerScanRadiusChunks());
        int nearest = Integer.MAX_VALUE;

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.getWorld().getName().equals(worldName)) {
                continue;
            }
            int playerChunkX = player.getLocation().getBlockX() >> 4;
            int playerChunkZ = player.getLocation().getBlockZ() >> 4;
            int distanceSquared = square(chunkCoord.x() - playerChunkX) + square(chunkCoord.z() - playerChunkZ);
            if (distanceSquared <= radiusSquared && distanceSquared < nearest) {
                nearest = distanceSquared;
            }
        }

        return nearest == Integer.MAX_VALUE ? -1 : nearest;
    }

    private Double currentTps() {
        try {
            Method method = Bukkit.getServer().getClass().getMethod("getTPS");
            Object result = method.invoke(Bukkit.getServer());
            if (result instanceof double[] values && values.length > 0) {
                return values[0];
            }
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
        return null;
    }

    private void clearActiveJob() {
        if (activeJob != null) {
            activeJob.cancel();
            activeJob = null;
        }
        activeJobDescription = null;
    }

    private void sendProgress(CommandSender sender, String message) {
        if (sender instanceof Player player && !player.isOnline()) {
            return;
        }
        sender.sendMessage(message);
    }

    private int square(int value) {
        return value * value;
    }

    private record Budget(int chunksPerTick, int blockChecksPerTick, int restoreBlocksPerTick) {
    }
}
