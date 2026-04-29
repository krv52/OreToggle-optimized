package org.example;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class OreTogglePlugin extends JavaPlugin implements Listener {

    private static final double LOW_TPS_THRESHOLD = 18.0D;
    private static final long CHUNK_LOAD_WINDOW_MILLIS = 4_000L;

    private final Map<String, OreDefinition> oreDefinitions = new HashMap<>();
    private final Map<String, OreRuntimeState> oreRuntimeStates = new HashMap<>();
    private final Map<String, Map<String, Set<String>>> removedBlocks = new HashMap<>();
    private final Map<String, Set<String>> processedChunks = new HashMap<>();
    private final Map<String, Set<String>> queuedChunks = new HashMap<>();
    private final Deque<PendingChunk> pendingChunks = new ArrayDeque<>();
    private final Deque<Long> recentChunkLoads = new ArrayDeque<>();

    private File stateFile;
    private boolean saveQueued;
    private BukkitTask chunkProcessorTask;
    private BukkitTask activeJob;
    private String activeJobDescription;
    private long schedulerTick;

    private int chunksPerTick;
    private int restoreBlocksPerTick;
    private int chunkProcessDelayTicks;
    private int maxPendingChunksPerTick;
    private int progressMessageIntervalTicks;
    private int maxQueueSize;
    private double lowTpsThreshold;
    private int minChunksPerTickUnderLoad;

    private boolean queueCapWarningLogged;
    private boolean lowTpsWarningLogged;

    @Override
    public void onEnable() {
        registerOreDefinitions();
        saveDefaultConfig();
        reloadConfig();
        loadSettingsFromConfig();
        stateFile = new File(getDataFolder(), "ore-state.yml");
        loadState();
        Bukkit.getPluginManager().registerEvents(this, this);
        enqueueLoadedChunksForDisabledOres();
        ensureChunkProcessorRunning();
    }

    @Override
    public void onDisable() {
        if (chunkProcessorTask != null) {
            chunkProcessorTask.cancel();
            chunkProcessorTask = null;
        }
        if (activeJob != null) {
            activeJob.cancel();
            activeJob = null;
            activeJobDescription = null;
        }
        flushStateSync();
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        recentChunkLoads.addLast(System.currentTimeMillis());

        boolean queued = false;
        for (Map.Entry<String, OreRuntimeState> entry : oreRuntimeStates.entrySet()) {
            if (entry.getValue() != OreRuntimeState.DISABLED) {
                continue;
            }

            queued |= enqueueChunkForOre(entry.getKey(), event.getChunk(), schedulerTick + chunkProcessDelayTicks);
        }

        if (queued) {
            ensureChunkProcessorRunning();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String commandName = command.getName().toLowerCase(Locale.ROOT);
        return switch (commandName) {
            case "toggleore", "oretoggle" -> handleToggleOre(sender, args);
            case "restoreore" -> handleRestoreOre(sender, args);
            default -> false;
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return oreDefinitions.keySet().stream()
                    .filter(key -> key.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .sorted()
                    .toList();
        }

        if ((command.getName().equalsIgnoreCase("toggleore") || command.getName().equalsIgnoreCase("oretoggle")) && args.length == 2) {
            return List.of("on", "off").stream()
                    .filter(value -> value.startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }

        return Collections.emptyList();
    }

    private boolean handleToggleOre(CommandSender sender, String[] args) {
        if (args.length != 2) {
            sender.sendMessage("Usage: /toggleore <ore> <on|off>");
            return true;
        }

        if (hasActiveJob()) {
            sender.sendMessage("Another ore operation is already running: " + activeJobDescription);
            return true;
        }

        OreDefinition definition = oreDefinitions.get(normalizeKey(args[0]));
        if (definition == null) {
            sender.sendMessage("Unknown ore. Available: " + String.join(", ", oreDefinitions.keySet()));
            return true;
        }

        String mode = args[1].toLowerCase(Locale.ROOT);
        if (!mode.equals("on") && !mode.equals("off")) {
            sender.sendMessage("Second argument must be on or off.");
            return true;
        }

        if (mode.equals("on")) {
            oreRuntimeStates.put(definition.key(), OreRuntimeState.ENABLED);
            removePendingChunksForOre(definition.key());
            persistState();
            sender.sendMessage(definition.displayName() + " is now enabled. New chunks will not be processed.");
            return true;
        }

        oreRuntimeStates.put(definition.key(), OreRuntimeState.DISABLED);
        int queued = enqueueLoadedChunksForOre(definition.key(), 1L);
        ensureChunkProcessorRunning();
        persistState();
        sender.sendMessage(
                definition.displayName() + " is now disabled. Queued " + queued + " loaded chunks. "
                        + "New chunks will be processed lazily after a delay."
        );
        return true;
    }

    private boolean handleRestoreOre(CommandSender sender, String[] args) {
        if (args.length != 1) {
            sender.sendMessage("Usage: /restoreore <ore>");
            return true;
        }

        if (hasActiveJob()) {
            sender.sendMessage("Another ore operation is already running: " + activeJobDescription);
            return true;
        }

        OreDefinition definition = oreDefinitions.get(normalizeKey(args[0]));
        if (definition == null) {
            sender.sendMessage("Unknown ore. Available: " + String.join(", ", oreDefinitions.keySet()));
            return true;
        }

        Deque<RestoreTarget> queue = collectRestoreTargets(definition);
        if (queue.isEmpty()) {
            oreRuntimeStates.put(definition.key(), OreRuntimeState.ENABLED);
            removePendingChunksForOre(definition.key());
            processedChunks.remove(definition.key());
            persistState();
            sender.sendMessage("No saved blocks found for " + definition.displayName() + ".");
            return true;
        }

        startRestoreJob(sender, definition, queue);
        return true;
    }

    private void loadSettingsFromConfig() {
        FileConfiguration config = getConfig();
        chunksPerTick = Math.max(1, config.getInt("chunks-per-tick", 1));
        restoreBlocksPerTick = Math.max(1, config.getInt("restore-blocks-per-tick", 64));
        chunkProcessDelayTicks = Math.max(1, config.getInt("chunk-process-delay-ticks", 80));
        maxPendingChunksPerTick = Math.max(chunksPerTick, config.getInt("max-pending-chunks-per-tick", 8));
        progressMessageIntervalTicks = Math.max(20, config.getInt("progress-message-interval-ticks", 100));
        maxQueueSize = Math.max(1, config.getInt("max-queue-size", 2000));
        lowTpsThreshold = config.getDouble("low-tps-threshold", LOW_TPS_THRESHOLD);
        minChunksPerTickUnderLoad = Math.max(1, config.getInt("min-chunks-per-tick-under-load", 1));
    }

    private void enqueueLoadedChunksForDisabledOres() {
        for (Map.Entry<String, OreRuntimeState> entry : oreRuntimeStates.entrySet()) {
            if (entry.getValue() == OreRuntimeState.DISABLED) {
                enqueueLoadedChunksForOre(entry.getKey(), 1L);
            }
        }
    }

    private int enqueueLoadedChunksForOre(String oreKey, long delayTicks) {
        int queued = 0;
        long readyTick = schedulerTick + Math.max(1L, delayTicks);
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                if (enqueueChunkForOre(oreKey, chunk, readyTick)) {
                    queued++;
                }
            }
        }
        return queued;
    }

    private boolean enqueueChunkForOre(String oreKey, Chunk chunk, long readyTick) {
        String processedKey = processedKey(oreKey, chunk.getWorld().getName(), chunk.getX(), chunk.getZ());
        if (processedChunks.computeIfAbsent(oreKey, key -> new HashSet<>()).contains(processedKey)) {
            return false;
        }

        Set<String> queuedForOre = queuedChunks.computeIfAbsent(oreKey, key -> new HashSet<>());
        if (!queuedForOre.add(processedKey)) {
            return false;
        }

        capQueueIfNeeded();
        pendingChunks.addLast(new PendingChunk(oreKey, chunk.getWorld().getName(), chunk.getX(), chunk.getZ(), readyTick));
        if (pendingChunks.size() >= maxQueueSize && !queueCapWarningLogged) {
            getLogger().warning("Pending ore chunk queue reached cap of " + maxQueueSize + ". Oldest entries will be dropped.");
            queueCapWarningLogged = true;
        } else if (pendingChunks.size() < maxQueueSize) {
            queueCapWarningLogged = false;
        }
        return true;
    }

    private void removePendingChunksForOre(String oreKey) {
        pendingChunks.removeIf(pending -> pending.oreKey().equals(oreKey));
        queuedChunks.remove(oreKey);
        if (pendingChunks.size() < maxQueueSize) {
            queueCapWarningLogged = false;
        }
    }

    private void capQueueIfNeeded() {
        while (pendingChunks.size() >= maxQueueSize && !pendingChunks.isEmpty()) {
            PendingChunk dropped = pendingChunks.pollFirst();
            if (dropped == null) {
                break;
            }

            Set<String> queuedForOre = queuedChunks.get(dropped.oreKey());
            if (queuedForOre != null) {
                queuedForOre.remove(dropped.processedKey());
                if (queuedForOre.isEmpty()) {
                    queuedChunks.remove(dropped.oreKey());
                }
            }
        }
    }

    private void ensureChunkProcessorRunning() {
        if (chunkProcessorTask != null && !chunkProcessorTask.isCancelled()) {
            return;
        }

        chunkProcessorTask = Bukkit.getScheduler().runTaskTimer(this, this::processPendingChunks, 1L, 1L);
    }

    private void processPendingChunks() {
        schedulerTick++;
        trimRecentChunkLoads();

        if (pendingChunks.isEmpty()) {
            if (chunkProcessorTask != null) {
                chunkProcessorTask.cancel();
                chunkProcessorTask = null;
            }
            queueCapWarningLogged = false;
            lowTpsWarningLogged = false;
            return;
        }

        if (hasActiveJob()) {
            return;
        }

        boolean underLoad = isServerUnderLoad();
        int allowedChunksThisTick = underLoad ? Math.min(chunksPerTick, minChunksPerTickUnderLoad) : chunksPerTick;
        if (allowedChunksThisTick < 1) {
            allowedChunksThisTick = 1;
        }

        if (underLoad && !lowTpsWarningLogged) {
            getLogger().info("Ore chunk processing slowed due to low TPS or chunk-generation load.");
            lowTpsWarningLogged = true;
        } else if (!underLoad) {
            lowTpsWarningLogged = false;
        }

        int examined = 0;
        int processedCount = 0;
        boolean persistNeeded = false;

        while (examined < maxPendingChunksPerTick && processedCount < allowedChunksThisTick && !pendingChunks.isEmpty()) {
            PendingChunk pending = pendingChunks.pollFirst();
            if (pending == null) {
                break;
            }

            examined++;

            if (pending.readyTick() > schedulerTick) {
                pendingChunks.addLast(pending);
                continue;
            }

            queuedChunks.computeIfAbsent(pending.oreKey(), key -> new HashSet<>()).remove(pending.processedKey());

            if (oreRuntimeStates.getOrDefault(pending.oreKey(), OreRuntimeState.ENABLED) != OreRuntimeState.DISABLED) {
                continue;
            }

            World world = Bukkit.getWorld(pending.worldName());
            if (world == null || !world.isChunkLoaded(pending.chunkX(), pending.chunkZ())) {
                continue;
            }

            OreDefinition definition = oreDefinitions.get(pending.oreKey());
            if (definition == null) {
                continue;
            }

            Chunk chunk = world.getChunkAt(pending.chunkX(), pending.chunkZ());
            processChunkForOre(chunk, definition);
            persistNeeded = true;
            processedCount++;
        }

        if (persistNeeded) {
            persistState();
        }
    }

    private boolean isServerUnderLoad() {
        Double tps = currentTps();
        if (tps != null && tps < lowTpsThreshold) {
            return true;
        }

        int chunkLoadThreshold = Math.max(6, chunksPerTick * 4);
        return recentChunkLoads.size() >= chunkLoadThreshold;
    }

    private void trimRecentChunkLoads() {
        long cutoff = System.currentTimeMillis() - CHUNK_LOAD_WINDOW_MILLIS;
        while (!recentChunkLoads.isEmpty() && recentChunkLoads.peekFirst() < cutoff) {
            recentChunkLoads.pollFirst();
        }
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

    private int processChunkForOre(Chunk chunk, OreDefinition definition) {
        String processedKey = processedKey(definition.key(), chunk.getWorld().getName(), chunk.getX(), chunk.getZ());
        Set<String> processedForOre = processedChunks.computeIfAbsent(definition.key(), key -> new HashSet<>());
        if (!processedForOre.add(processedKey)) {
            return 0;
        }

        int removed = 0;
        int minY = chunk.getWorld().getMinHeight();
        int maxY = chunk.getWorld().getMaxHeight();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y < maxY; y++) {
                    Block block = chunk.getBlock(x, y, z);
                    if (!definition.matches(block.getType())) {
                        continue;
                    }

                    rememberRemovedBlock(definition.key(), chunk.getWorld().getName(), block);
                    block.setType(replacementFor(block.getType()), false);
                    removed++;
                }
            }
        }

        return removed;
    }

    private void startRestoreJob(CommandSender sender, OreDefinition definition, Deque<RestoreTarget> queue) {
        int totalBlocks = queue.size();
        oreRuntimeStates.put(definition.key(), OreRuntimeState.RESTORING);
        removePendingChunksForOre(definition.key());
        purgeDuplicatePendingChunks();
        persistState();
        sender.sendMessage("Started restoring " + definition.displayName() + ". Queued " + totalBlocks + " blocks.");
        getLogger().info("Starting ore restore for " + definition.key() + " with " + totalBlocks + " saved entries.");
        activeJobDescription = "restoreore " + definition.key();

        activeJob = Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            private int processedBlocksCount;
            private int restoredBlocksCount;
            private int ticksSinceProgress;
            private int lastReportedPercent = -1;

            @Override
            public void run() {
                if (queue.isEmpty()) {
                    oreRuntimeStates.put(definition.key(), OreRuntimeState.ENABLED);
                    removePendingChunksForOre(definition.key());
                    processedChunks.remove(definition.key());
                    persistState();
                    clearActiveJob();
                    getLogger().info("Finished ore restore for " + definition.key() + ". Restored " + restoredBlocksCount + " blocks.");
                    sender.sendMessage(
                            "Restored " + restoredBlocksCount + " blocks for " + definition.displayName()
                                    + ". Processed " + processedBlocksCount + "/" + totalBlocks + " saved entries."
                    );
                    return;
                }

                int batchCount = 0;
                while (batchCount < restoreBlocksPerTick && !queue.isEmpty()) {
                    RestoreTarget target = queue.pollFirst();
                    if (target == null) {
                        continue;
                    }

                    World world = Bukkit.getWorld(target.worldName());
                    if (world != null) {
                        Block block = world.getBlockAt(target.savedBlock().x(), target.savedBlock().y(), target.savedBlock().z());
                        block.setType(target.savedBlock().material(), false);
                        restoredBlocksCount++;
                    }

                    removeSavedBlock(definition.key(), target.worldName(), target.serialized());
                    processedBlocksCount++;
                    batchCount++;
                }

                ticksSinceProgress++;
                int currentPercent = totalBlocks == 0 ? 100 : (processedBlocksCount * 100) / totalBlocks;
                if (processedBlocksCount > 0 && ticksSinceProgress >= progressMessageIntervalTicks && currentPercent >= lastReportedPercent + 1) {
                    sendProgress(sender, "Restore progress for " + definition.displayName() + ": " + processedBlocksCount + "/" + totalBlocks + " entries.");
                    ticksSinceProgress = 0;
                    lastReportedPercent = currentPercent;
                }
            }
        }, 1L, 1L);
    }

    private Deque<RestoreTarget> collectRestoreTargets(OreDefinition definition) {
        Deque<RestoreTarget> queue = new ArrayDeque<>();
        Map<String, Set<String>> perWorld = removedBlocks.getOrDefault(definition.key(), Collections.emptyMap());

        for (Map.Entry<String, Set<String>> entry : perWorld.entrySet()) {
            for (String serialized : new ArrayList<>(entry.getValue())) {
                SavedBlock savedBlock = SavedBlock.deserialize(serialized);
                if (savedBlock == null) {
                    removeSavedBlock(definition.key(), entry.getKey(), serialized);
                    continue;
                }

                queue.addLast(new RestoreTarget(entry.getKey(), serialized, savedBlock));
            }
        }

        return queue;
    }

    private void removeSavedBlock(String oreKey, String worldName, String serialized) {
        Map<String, Set<String>> perWorld = removedBlocks.get(oreKey);
        if (perWorld == null) {
            return;
        }

        Set<String> positions = perWorld.get(worldName);
        if (positions == null) {
            return;
        }

        positions.remove(serialized);
        if (positions.isEmpty()) {
            perWorld.remove(worldName);
        }
        if (perWorld.isEmpty()) {
            removedBlocks.remove(oreKey);
        }
    }

    private void rememberRemovedBlock(String oreKey, String worldName, Block block) {
        removedBlocks
                .computeIfAbsent(oreKey, key -> new HashMap<>())
                .computeIfAbsent(worldName, key -> new HashSet<>())
                .add(SavedBlock.serialize(block));
    }

    private void purgeDuplicatePendingChunks() {
        Set<String> seen = new HashSet<>();
        pendingChunks.removeIf(pending -> !seen.add(pending.processedKey()));
        queuedChunks.clear();
        for (PendingChunk pending : pendingChunks) {
            queuedChunks.computeIfAbsent(pending.oreKey(), key -> new HashSet<>()).add(pending.processedKey());
        }
    }

    private Material replacementFor(Material oreMaterial) {
        String name = oreMaterial.name();
        if (name.startsWith("DEEPSLATE_")) {
            return Material.DEEPSLATE;
        }
        if (name.contains("NETHER") || oreMaterial == Material.ANCIENT_DEBRIS || oreMaterial == Material.NETHER_QUARTZ_ORE) {
            return Material.NETHERRACK;
        }
        return Material.STONE;
    }

    private void registerOreDefinitions() {
        addOre("coal", "Coal ore", Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE);
        addOre("iron", "Iron ore", Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE);
        addOre("gold", "Gold ore", Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE);
        addOre("redstone", "Redstone ore", Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE);
        addOre("lapis", "Lapis ore", Material.LAPIS_ORE, Material.DEEPSLATE_LAPIS_ORE);
        addOre("diamond", "Diamond ore", Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE);
        addOre("emerald", "Emerald ore", Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE);
        addOre("copper", "Copper ore", Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE);
        addOre("quartz", "Quartz ore", Material.NETHER_QUARTZ_ORE);
        addOre("nether_gold", "Nether gold ore", Material.NETHER_GOLD_ORE);
        addOre("debris", "Ancient debris", Material.ANCIENT_DEBRIS);
    }

    private void addOre(String key, String displayName, Material... materials) {
        oreDefinitions.put(key, new OreDefinition(key, displayName, EnumSet.copyOf(List.of(materials))));
    }

    private void loadState() {
        FileConfiguration config = YamlConfiguration.loadConfiguration(stateFile);

        for (String oreKey : oreDefinitions.keySet()) {
            String storedState = config.getString("ore-states." + oreKey);
            if (storedState != null) {
                oreRuntimeStates.put(oreKey, deserializeRuntimeState(storedState));
                continue;
            }

            boolean enabled = config.getBoolean("ore-states." + oreKey, true);
            oreRuntimeStates.put(oreKey, enabled ? OreRuntimeState.ENABLED : OreRuntimeState.DISABLED);
        }

        removedBlocks.clear();
        ConfigurationSection removedSection = config.getConfigurationSection("removed-blocks");
        if (removedSection != null) {
            for (String oreKey : removedSection.getKeys(false)) {
                ConfigurationSection worldsSection = removedSection.getConfigurationSection(oreKey);
                if (worldsSection == null) {
                    continue;
                }

                Map<String, Set<String>> perWorld = new HashMap<>();
                for (String worldName : worldsSection.getKeys(false)) {
                    perWorld.put(worldName, new HashSet<>(worldsSection.getStringList(worldName)));
                }
                removedBlocks.put(oreKey, perWorld);
            }
        }

        processedChunks.clear();
        ConfigurationSection processedSection = config.getConfigurationSection("processed-chunks");
        if (processedSection != null) {
            for (String oreKey : processedSection.getKeys(false)) {
                processedChunks.put(oreKey, new HashSet<>(processedSection.getStringList(oreKey)));
            }
        }
    }

    private void persistState() {
        if (saveQueued) {
            return;
        }

        saveQueued = true;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            saveQueued = false;
            YamlConfiguration snapshot = buildStateSnapshot();
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> saveSnapshot(snapshot));
        }, 20L);
    }

    private void flushStateSync() {
        saveQueued = false;
        saveSnapshot(buildStateSnapshot());
    }

    private YamlConfiguration buildStateSnapshot() {
        YamlConfiguration config = new YamlConfiguration();

        for (Map.Entry<String, OreRuntimeState> entry : oreRuntimeStates.entrySet()) {
            config.set("ore-states." + entry.getKey(), entry.getValue().name().toLowerCase(Locale.ROOT));
        }

        for (Map.Entry<String, Map<String, Set<String>>> oreEntry : removedBlocks.entrySet()) {
            for (Map.Entry<String, Set<String>> worldEntry : oreEntry.getValue().entrySet()) {
                config.set("removed-blocks." + oreEntry.getKey() + "." + worldEntry.getKey(), new ArrayList<>(worldEntry.getValue()));
            }
        }

        for (Map.Entry<String, Set<String>> entry : processedChunks.entrySet()) {
            config.set("processed-chunks." + entry.getKey(), new ArrayList<>(entry.getValue()));
        }

        return config;
    }

    private void saveSnapshot(YamlConfiguration snapshot) {
        try {
            if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
                getLogger().warning("Could not create plugin data folder for ore-state.yml");
                return;
            }

            snapshot.save(stateFile);
        } catch (IOException exception) {
            getLogger().severe("Failed to save ore-state.yml: " + exception.getMessage());
        }
    }

    private boolean hasActiveJob() {
        return activeJob != null && !activeJob.isCancelled();
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

    private String normalizeKey(String input) {
        return input.toLowerCase(Locale.ROOT);
    }

    private String processedKey(String oreKey, String worldName, int chunkX, int chunkZ) {
        return worldName + ":" + chunkX + ":" + chunkZ + ":" + oreKey;
    }

    private OreRuntimeState deserializeRuntimeState(String value) {
        try {
            OreRuntimeState state = OreRuntimeState.valueOf(value.toUpperCase(Locale.ROOT));
            return state == OreRuntimeState.RESTORING ? OreRuntimeState.ENABLED : state;
        } catch (IllegalArgumentException exception) {
            return OreRuntimeState.ENABLED;
        }
    }

    private record OreDefinition(String key, String displayName, Set<Material> materials) {
        boolean matches(Material material) {
            return materials.contains(material);
        }
    }

    private record PendingChunk(String oreKey, String worldName, int chunkX, int chunkZ, long readyTick) {
        String processedKey() {
            return worldName + ":" + chunkX + ":" + chunkZ + ":" + oreKey;
        }
    }

    private record SavedBlock(int x, int y, int z, Material material) {
        static String serialize(Block block) {
            return block.getX() + "," + block.getY() + "," + block.getZ() + "," + block.getType().name();
        }

        static SavedBlock deserialize(String value) {
            String[] parts = value.split(",");
            if (parts.length != 4) {
                return null;
            }

            try {
                return new SavedBlock(
                        Integer.parseInt(parts[0]),
                        Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2]),
                        Material.valueOf(parts[3])
                );
            } catch (IllegalArgumentException exception) {
                return null;
            }
        }
    }

    private record RestoreTarget(String worldName, String serialized, SavedBlock savedBlock) {
    }

    private enum OreRuntimeState {
        ENABLED,
        DISABLED,
        RESTORING
    }
}
