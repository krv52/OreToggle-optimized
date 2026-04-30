package me.krv.oretoggle;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

final class StateStorage {
    private final File stateFile;
    private final Logger logger;

    StateStorage(File stateFile, Logger logger) {
        this.stateFile = stateFile;
        this.logger = logger;
    }

    StateSnapshot load(Set<String> knownOreKeys) {
        FileConfiguration config = YamlConfiguration.loadConfiguration(stateFile);
        Map<String, OreRuntimeState> states = loadStates(config, knownOreKeys);
        Map<String, Map<String, Map<ChunkCoord, Set<SavedBlock>>>> removedBlocks = loadRemovedBlocks(config);
        Map<String, Set<ChunkRef>> processedChunks = loadProcessedChunks(config);

        StateSnapshot snapshot = new StateSnapshot(states, removedBlocks, processedChunks);
        logger.info("Loaded ore cache: removedBlocks=" + snapshot.removedBlockCount()
                + ", processedChunks=" + snapshot.processedChunkCount()
                + ", states=" + states.size() + ".");
        return snapshot;
    }

    void save(StateSnapshot snapshot) {
        YamlConfiguration config = new YamlConfiguration();
        for (Map.Entry<String, OreRuntimeState> entry : snapshot.oreStates().entrySet()) {
            config.set("ore-states." + entry.getKey(), entry.getValue().name().toLowerCase(Locale.ROOT));
        }

        for (Map.Entry<String, Map<String, Map<ChunkCoord, Set<SavedBlock>>>> oreEntry : snapshot.removedBlocks().entrySet()) {
            for (Map.Entry<String, Map<ChunkCoord, Set<SavedBlock>>> worldEntry : oreEntry.getValue().entrySet()) {
                ArrayList<String> serializedBlocks = new ArrayList<>();
                for (Set<SavedBlock> chunkBlocks : worldEntry.getValue().values()) {
                    for (SavedBlock savedBlock : chunkBlocks) {
                        serializedBlocks.add(savedBlock.serialize());
                    }
                }
                config.set("removed-blocks." + oreEntry.getKey() + "." + worldEntry.getKey(), serializedBlocks);
            }
        }

        for (Map.Entry<String, Set<ChunkRef>> entry : snapshot.processedChunks().entrySet()) {
            ArrayList<String> serializedChunks = new ArrayList<>();
            for (ChunkRef chunkRef : entry.getValue()) {
                serializedChunks.add(chunkRef.serialize(entry.getKey()));
            }
            config.set("processed-chunks." + entry.getKey(), serializedChunks);
        }

        try {
            if (!stateFile.getParentFile().exists() && !stateFile.getParentFile().mkdirs()) {
                logger.warning("Could not create plugin data folder for ore-state.yml");
                return;
            }
            config.save(stateFile);
            logger.info("Saved ore cache: removedBlocks=" + snapshot.removedBlockCount()
                    + ", processedChunks=" + snapshot.processedChunkCount() + ".");
        } catch (IOException exception) {
            logger.severe("Failed to save ore-state.yml: " + exception.getMessage());
        }
    }

    private Map<String, OreRuntimeState> loadStates(FileConfiguration config, Set<String> knownOreKeys) {
        Map<String, OreRuntimeState> states = new HashMap<>();
        for (String oreKey : knownOreKeys) {
            String storedState = config.getString("ore-states." + oreKey);
            if (storedState != null) {
                states.put(oreKey, deserializeRuntimeState(storedState));
                continue;
            }
            boolean enabled = config.getBoolean("ore-states." + oreKey, true);
            states.put(oreKey, enabled ? OreRuntimeState.ENABLED : OreRuntimeState.DISABLED);
        }
        return states;
    }

    private Map<String, Map<String, Map<ChunkCoord, Set<SavedBlock>>>> loadRemovedBlocks(FileConfiguration config) {
        Map<String, Map<String, Map<ChunkCoord, Set<SavedBlock>>>> removedBlocks = new HashMap<>();
        ConfigurationSection removedSection = config.getConfigurationSection("removed-blocks");
        if (removedSection == null) {
            return removedBlocks;
        }

        for (String oreKey : removedSection.getKeys(false)) {
            ConfigurationSection worldsSection = removedSection.getConfigurationSection(oreKey);
            if (worldsSection == null) {
                continue;
            }

            Map<String, Map<ChunkCoord, Set<SavedBlock>>> perWorld = new HashMap<>();
            for (String worldName : worldsSection.getKeys(false)) {
                Map<ChunkCoord, Set<SavedBlock>> perChunk = new HashMap<>();
                for (String serialized : worldsSection.getStringList(worldName)) {
                    SavedBlock savedBlock = SavedBlock.deserialize(serialized);
                    if (savedBlock == null || savedBlock.material() == Material.AIR) {
                        continue;
                    }
                    perChunk.computeIfAbsent(ChunkCoord.fromBlock(savedBlock.x(), savedBlock.z()), key -> new HashSet<>()).add(savedBlock);
                }
                if (!perChunk.isEmpty()) {
                    perWorld.put(worldName, perChunk);
                }
            }
            if (!perWorld.isEmpty()) {
                removedBlocks.put(oreKey, perWorld);
            }
        }
        return removedBlocks;
    }

    private Map<String, Set<ChunkRef>> loadProcessedChunks(FileConfiguration config) {
        Map<String, Set<ChunkRef>> processedChunks = new HashMap<>();
        ConfigurationSection processedSection = config.getConfigurationSection("processed-chunks");
        if (processedSection == null) {
            return processedChunks;
        }

        for (String oreKey : processedSection.getKeys(false)) {
            Set<ChunkRef> chunks = new HashSet<>();
            for (String value : processedSection.getStringList(oreKey)) {
                ChunkRef chunkRef = deserializeProcessedChunk(value);
                if (chunkRef != null) {
                    chunks.add(chunkRef);
                }
            }
            if (!chunks.isEmpty()) {
                processedChunks.put(oreKey, chunks);
            }
        }
        return processedChunks;
    }

    private ChunkRef deserializeProcessedChunk(String value) {
        String[] parts = value.split(":");
        try {
            if (parts.length == 4) {
                return new ChunkRef(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
            }
            if (parts.length == 3 && isInteger(parts[0])) {
                return new ChunkRef("world", Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
            }
        } catch (NumberFormatException ignored) {
            return null;
        }
        return null;
    }

    private boolean isInteger(String value) {
        try {
            Integer.parseInt(value);
            return true;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private OreRuntimeState deserializeRuntimeState(String value) {
        if (value.equalsIgnoreCase("true")) {
            return OreRuntimeState.ENABLED;
        }
        if (value.equalsIgnoreCase("false")) {
            return OreRuntimeState.DISABLED;
        }

        try {
            OreRuntimeState state = OreRuntimeState.valueOf(value.toUpperCase(Locale.ROOT));
            return state == OreRuntimeState.RESTORING ? OreRuntimeState.ENABLED : state;
        } catch (IllegalArgumentException exception) {
            return OreRuntimeState.ENABLED;
        }
    }
}
