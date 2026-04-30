package org.example;

record PendingChunk(String oreKey, String worldName, ChunkCoord chunkCoord, long readyTick, int distanceSquared, long sequence) {
    String key() {
        return oreKey + ":" + worldName + ":" + chunkCoord.x() + ":" + chunkCoord.z();
    }
}
