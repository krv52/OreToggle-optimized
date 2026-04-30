package me.krv.oretoggle;

import org.bukkit.configuration.file.FileConfiguration;

record ConfigSettings(
        int chunksPerTick,
        int restoreBlocksPerTick,
        int chunkProcessDelayTicks,
        int maxPendingChunksPerTick,
        int maxBlockChecksPerTick,
        int progressMessageIntervalTicks,
        int playerScanRadiusChunks,
        int playerScanIntervalTicks,
        int maxQueueSize,
        double lowTpsThreshold,
        int minChunksPerTickUnderLoad,
        boolean debug,
        long autosaveIntervalSeconds
) {
    static ConfigSettings from(FileConfiguration config) {
        int chunksPerTick = Math.max(1, config.getInt("chunks-per-tick", 2));
        return new ConfigSettings(
                chunksPerTick,
                Math.max(1, config.getInt("restore-blocks-per-tick", 1000)),
                Math.max(1, config.getInt("chunk-process-delay-ticks", 20)),
                Math.max(chunksPerTick, config.getInt("max-pending-chunks-per-tick", 8)),
                Math.max(256, config.getInt("max-block-checks-per-tick", 8000)),
                Math.max(20, config.getInt("progress-message-interval-ticks", 100)),
                Math.max(0, config.getInt("player-scan-radius-chunks", 5)),
                Math.max(1, config.getInt("player-scan-interval-ticks", 60)),
                Math.max(1, config.getInt("max-queue-size", 2000)),
                config.getDouble("low-tps-threshold", 16.0D),
                Math.max(1, config.getInt("min-chunks-per-tick-under-load", 1)),
                config.getBoolean("debug", false),
                config.getLong("autosave-interval", 300L)
        );
    }
}
