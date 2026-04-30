package org.example;

import org.bukkit.block.Block;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class OreCacheManager {
    private final Map<String, OreRuntimeState> oreStates = new HashMap<>();
    private final Map<String, Map<String, Map<ChunkCoord, Set<SavedBlock>>>> removedBlocks = new HashMap<>();
    private final Map<String, Set<ChunkRef>> processedChunks = new HashMap<>();

    void load(StateSnapshot snapshot) {
        oreStates.clear();
        oreStates.putAll(snapshot.oreStates());

        removedBlocks.clear();
        for (Map.Entry<String, Map<String, Map<ChunkCoord, Set<SavedBlock>>>> oreEntry : snapshot.removedBlocks().entrySet()) {
            Map<String, Map<ChunkCoord, Set<SavedBlock>>> perWorld = new HashMap<>();
            for (Map.Entry<String, Map<ChunkCoord, Set<SavedBlock>>> worldEntry : oreEntry.getValue().entrySet()) {
                Map<ChunkCoord, Set<SavedBlock>> perChunk = new HashMap<>();
                for (Map.Entry<ChunkCoord, Set<SavedBlock>> chunkEntry : worldEntry.getValue().entrySet()) {
                    perChunk.put(chunkEntry.getKey(), new HashSet<>(chunkEntry.getValue()));
                }
                perWorld.put(worldEntry.getKey(), perChunk);
            }
            removedBlocks.put(oreEntry.getKey(), perWorld);
        }

        processedChunks.clear();
        for (Map.Entry<String, Set<ChunkRef>> entry : snapshot.processedChunks().entrySet()) {
            processedChunks.put(entry.getKey(), new HashSet<>(entry.getValue()));
        }
    }

    StateSnapshot snapshot() {
        Map<String, OreRuntimeState> statesCopy = new HashMap<>(oreStates);
        Map<String, Map<String, Map<ChunkCoord, Set<SavedBlock>>>> removedCopy = new HashMap<>();
        for (Map.Entry<String, Map<String, Map<ChunkCoord, Set<SavedBlock>>>> oreEntry : removedBlocks.entrySet()) {
            Map<String, Map<ChunkCoord, Set<SavedBlock>>> perWorld = new HashMap<>();
            for (Map.Entry<String, Map<ChunkCoord, Set<SavedBlock>>> worldEntry : oreEntry.getValue().entrySet()) {
                Map<ChunkCoord, Set<SavedBlock>> perChunk = new HashMap<>();
                for (Map.Entry<ChunkCoord, Set<SavedBlock>> chunkEntry : worldEntry.getValue().entrySet()) {
                    perChunk.put(chunkEntry.getKey(), new HashSet<>(chunkEntry.getValue()));
                }
                perWorld.put(worldEntry.getKey(), perChunk);
            }
            removedCopy.put(oreEntry.getKey(), perWorld);
        }

        Map<String, Set<ChunkRef>> processedCopy = new HashMap<>();
        for (Map.Entry<String, Set<ChunkRef>> entry : processedChunks.entrySet()) {
            processedCopy.put(entry.getKey(), new HashSet<>(entry.getValue()));
        }

        return new StateSnapshot(statesCopy, removedCopy, processedCopy);
    }

    OreRuntimeState state(String oreKey) {
        return oreStates.getOrDefault(oreKey, OreRuntimeState.ENABLED);
    }

    List<String> disabledOreKeys() {
        return oreStates.entrySet().stream()
                .filter(entry -> entry.getValue() == OreRuntimeState.DISABLED)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    void setState(String oreKey, OreRuntimeState state) {
        oreStates.put(oreKey, state);
    }

    boolean addRemovedBlock(String oreKey, String worldName, Block block) {
        SavedBlock savedBlock = SavedBlock.from(block);
        return removedBlocks
                .computeIfAbsent(oreKey, key -> new HashMap<>())
                .computeIfAbsent(worldName, key -> new HashMap<>())
                .computeIfAbsent(ChunkCoord.fromBlock(savedBlock.x(), savedBlock.z()), key -> new HashSet<>())
                .add(savedBlock);
    }

    boolean isRemoved(Block block) {
        ChunkCoord chunkCoord = ChunkCoord.fromBlock(block.getX(), block.getZ());
        for (Map<String, Map<ChunkCoord, Set<SavedBlock>>> perWorld : removedBlocks.values()) {
            Map<ChunkCoord, Set<SavedBlock>> perChunk = perWorld.get(block.getWorld().getName());
            if (perChunk == null) {
                continue;
            }
            Set<SavedBlock> savedBlocks = perChunk.get(chunkCoord);
            if (savedBlocks == null) {
                continue;
            }
            for (SavedBlock savedBlock : savedBlocks) {
                if (savedBlock.isAt(block)) {
                    return true;
                }
            }
        }
        return false;
    }

    Deque<RestoreTarget> collectRestoreTargets(String oreKey) {
        Deque<RestoreTarget> targets = new ArrayDeque<>();
        Map<String, Map<ChunkCoord, Set<SavedBlock>>> perWorld = removedBlocks.getOrDefault(oreKey, Map.of());
        for (Map.Entry<String, Map<ChunkCoord, Set<SavedBlock>>> worldEntry : perWorld.entrySet()) {
            for (Map.Entry<ChunkCoord, Set<SavedBlock>> chunkEntry : worldEntry.getValue().entrySet()) {
                for (SavedBlock savedBlock : new ArrayList<>(chunkEntry.getValue())) {
                    targets.addLast(new RestoreTarget(oreKey, worldEntry.getKey(), chunkEntry.getKey(), savedBlock));
                }
            }
        }
        return targets;
    }

    void removeSavedBlock(RestoreTarget target) {
        Map<String, Map<ChunkCoord, Set<SavedBlock>>> perWorld = removedBlocks.get(target.oreKey());
        if (perWorld == null) {
            return;
        }
        Map<ChunkCoord, Set<SavedBlock>> perChunk = perWorld.get(target.worldName());
        if (perChunk == null) {
            return;
        }
        Set<SavedBlock> savedBlocks = perChunk.get(target.chunkCoord());
        if (savedBlocks == null) {
            return;
        }

        savedBlocks.remove(target.savedBlock());
        if (savedBlocks.isEmpty()) {
            perChunk.remove(target.chunkCoord());
        }
        if (perChunk.isEmpty()) {
            perWorld.remove(target.worldName());
        }
        if (perWorld.isEmpty()) {
            removedBlocks.remove(target.oreKey());
        }
    }

    boolean isProcessed(String oreKey, String worldName, ChunkCoord chunkCoord) {
        return processedChunks.getOrDefault(oreKey, Set.of()).contains(new ChunkRef(worldName, chunkCoord.x(), chunkCoord.z()));
    }

    void markProcessed(String oreKey, String worldName, ChunkCoord chunkCoord) {
        processedChunks.computeIfAbsent(oreKey, key -> new HashSet<>()).add(new ChunkRef(worldName, chunkCoord.x(), chunkCoord.z()));
    }

    void clearProcessed(String oreKey) {
        processedChunks.remove(oreKey);
    }
}
