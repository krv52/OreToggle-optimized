package me.krv.oretoggle;

import org.bukkit.Material;

import java.util.Set;

record OreDefinition(String key, String displayName, Set<Material> materials) {
    boolean matches(Material material) {
        return materials.contains(material);
    }
}
