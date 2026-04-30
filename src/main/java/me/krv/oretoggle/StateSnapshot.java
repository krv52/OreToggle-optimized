package me.krv.oretoggle;

import java.util.Map;
import java.util.Set;

record StateSnapshot(
        Map<String, OreRuntimeState> oreStates,
        Map<String, Map<String, Map<ChunkCoord, Set<SavedBlock>>>> removedBlocks,
        Map<String, Set<ChunkRef>> processedChunks
) {
    int removedBlockCount() {
        return removedBlocks.values().stream()
                .flatMap(perWorld -> perWorld.values().stream())
                .flatMap(perChunk -> perChunk.values().stream())
                .mapToInt(Set::size)
                .sum();
    }

    int processedChunkCount() {
        return processedChunks.values().stream().mapToInt(Set::size).sum();
    }
}
