package me.krv.oretoggle;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.logging.Logger;

final class OreScanner {
    private final OreCacheManager cacheManager;
    private final ConfigSettings settings;
    private final Logger logger;

    OreScanner(OreCacheManager cacheManager, ConfigSettings settings, Logger logger) {
        this.cacheManager = cacheManager;
        this.settings = settings;
        this.logger = logger;
    }

    ChunkScanResult continueScan(ProcessingChunk processingChunk, int budget) {
        World world = Bukkit.getWorld(processingChunk.worldName());
        ChunkCoord coord = processingChunk.chunkCoord();
        if (world == null || !world.isChunkLoaded(coord.x(), coord.z())) {
            return new ChunkScanResult(0, 0, true, false);
        }

        Chunk chunk = world.getChunkAt(coord.x(), coord.z());
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();
        int checks = 0;
        int removedThisTick = 0;

        while (checks < budget) {
            Block block = chunk.getBlock(processingChunk.x(), processingChunk.y(), processingChunk.z());
            if (processingChunk.definition().matches(block.getType()) && !cacheManager.isRemoved(block)) {
                cacheManager.addRemovedBlock(processingChunk.oreKey(), processingChunk.worldName(), block);
                block.setType(replacementFor(block.getType()), false);
                processingChunk.incrementRemovedCount();
                removedThisTick++;
            }

            checks++;
            processingChunk.y(processingChunk.y() + 1);
            if (processingChunk.y() >= maxY) {
                processingChunk.y(minY);
                processingChunk.z(processingChunk.z() + 1);
                if (processingChunk.z() >= 16) {
                    processingChunk.z(0);
                    processingChunk.x(processingChunk.x() + 1);
                    if (processingChunk.x() >= 16) {
                        if (settings.debug() && processingChunk.removedCount() > 0) {
                            logger.info("Removed " + processingChunk.removedCount() + " " + processingChunk.oreKey()
                                    + " blocks in " + processingChunk.worldName() + " chunk "
                                    + coord.x() + "," + coord.z() + ".");
                        }
                        return new ChunkScanResult(checks, removedThisTick, true, true);
                    }
                }
            }
        }

        return new ChunkScanResult(checks, removedThisTick, false, false);
    }

    private Material replacementFor(Material oreMaterial) {
        String name = oreMaterial.name();
        if (name.startsWith("DEEPSLATE_")) {
            return Material.DEEPSLATE;
        }
        if (name.contains("NETHER") || oreMaterial == Material.ANCIENT_DEBRIS || oreMaterial == Material.NETHER_QUARTZ_ORE) {
            return Material.NETHERRACK;
        }
        return Material.STONE;
    }
}
