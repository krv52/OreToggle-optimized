package me.krv.oretoggle;

final class ProcessingChunk {
    private final String oreKey;
    private final String worldName;
    private final ChunkCoord chunkCoord;
    private final OreDefinition definition;
    private int x;
    private int y;
    private int z;
    private int removedCount;

    ProcessingChunk(String oreKey, String worldName, ChunkCoord chunkCoord, OreDefinition definition, int y) {
        this.oreKey = oreKey;
        this.worldName = worldName;
        this.chunkCoord = chunkCoord;
        this.definition = definition;
        this.y = y;
    }

    String oreKey() {
        return oreKey;
    }

    String worldName() {
        return worldName;
    }

    ChunkCoord chunkCoord() {
        return chunkCoord;
    }

    OreDefinition definition() {
        return definition;
    }

    int x() {
        return x;
    }

    void x(int x) {
        this.x = x;
    }

    int y() {
        return y;
    }

    void y(int y) {
        this.y = y;
    }

    int z() {
        return z;
    }

    void z(int z) {
        this.z = z;
    }

    int removedCount() {
        return removedCount;
    }

    void incrementRemovedCount() {
        removedCount++;
    }
}
