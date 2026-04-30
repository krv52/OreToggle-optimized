package me.krv.oretoggle;

record ChunkRef(String worldName, int x, int z) {
    ChunkCoord coord() {
        return new ChunkCoord(x, z);
    }

    String serialize(String oreKey) {
        return worldName + ":" + x + ":" + z + ":" + oreKey;
    }
}
