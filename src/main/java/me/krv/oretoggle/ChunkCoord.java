package me.krv.oretoggle;

record ChunkCoord(int x, int z) {
    static ChunkCoord fromBlock(int blockX, int blockZ) {
        return new ChunkCoord(blockX >> 4, blockZ >> 4);
    }

    String serialize(String worldName, String oreKey) {
        return worldName + ":" + x + ":" + z + ":" + oreKey;
    }
}
