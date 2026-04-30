package me.krv.oretoggle;

import org.bukkit.Material;

import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class OreDefinitions {
    private final Map<String, OreDefinition> definitions = new LinkedHashMap<>();

    OreDefinitions() {
        add("coal", "Coal ore", Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE);
        add("iron", "Iron ore", Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE);
        add("gold", "Gold ore", Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE);
        add("redstone", "Redstone ore", Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE);
        add("lapis", "Lapis ore", Material.LAPIS_ORE, Material.DEEPSLATE_LAPIS_ORE);
        add("diamond", "Diamond ore", Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE);
        add("emerald", "Emerald ore", Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE);
        add("copper", "Copper ore", Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE);
        add("quartz", "Quartz ore", Material.NETHER_QUARTZ_ORE);
        add("nether_gold", "Nether gold ore", Material.NETHER_GOLD_ORE);
        add("debris", "Ancient debris", Material.ANCIENT_DEBRIS);
    }

    OreDefinition get(String key) {
        return definitions.get(key);
    }

    Set<String> keys() {
        return definitions.keySet();
    }

    Collection<OreDefinition> all() {
        return definitions.values();
    }

    private void add(String key, String displayName, Material... materials) {
        definitions.put(key, new OreDefinition(key, displayName, EnumSet.copyOf(List.of(materials))));
    }
}
