package org.example;

import org.bukkit.Material;
import org.bukkit.block.Block;

record SavedBlock(int x, int y, int z, Material material) {
    static SavedBlock from(Block block) {
        return new SavedBlock(block.getX(), block.getY(), block.getZ(), block.getType());
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

    String serialize() {
        return x + "," + y + "," + z + "," + material.name();
    }

    boolean isAt(Block block) {
        return x == block.getX() && y == block.getY() && z == block.getZ();
    }
}
