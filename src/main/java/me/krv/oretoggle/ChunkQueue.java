package me.krv.oretoggle;

import java.util.Comparator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

final class ChunkQueue {
    private final PriorityQueue<PendingChunk> pendingChunks = new PriorityQueue<>(
            Comparator.comparingInt(PendingChunk::distanceSquared).thenComparingLong(PendingChunk::sequence)
    );
    private final Map<String, PendingChunk> queuedChunks = new HashMap<>();
    private final int maxQueueSize;
    private long sequence;
    private boolean capWarningLogged;

    ChunkQueue(int maxQueueSize) {
        this.maxQueueSize = maxQueueSize;
    }

    boolean enqueue(String oreKey, String worldName, ChunkCoord chunkCoord, long readyTick, int distanceSquared) {
        PendingChunk pending = new PendingChunk(oreKey, worldName, chunkCoord, readyTick, distanceSquared, sequence++);
        PendingChunk existing = queuedChunks.get(pending.key());
        if (existing != null) {
            if (existing.distanceSquared() <= distanceSquared && existing.readyTick() <= readyTick) {
                return false;
            }
            pendingChunks.remove(existing);
            queuedChunks.put(pending.key(), pending);
            pendingChunks.add(pending);
            return false;
        }

        queuedChunks.put(pending.key(), pending);
        pendingChunks.add(pending);
        return capQueueIfNeeded(pending.key());
    }

    PendingChunk pollReady(long schedulerTick, int maxAttempts) {
        int attempts = Math.min(maxAttempts, pendingChunks.size());
        List<PendingChunk> delayed = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            PendingChunk pending = pendingChunks.poll();
            if (pending == null) {
                pendingChunks.addAll(delayed);
                return null;
            }
            if (pending.readyTick() > schedulerTick) {
                delayed.add(pending);
                continue;
            }
            queuedChunks.remove(pending.key());
            pendingChunks.addAll(delayed);
            return pending;
        }
        pendingChunks.addAll(delayed);
        return null;
    }

    void removeOre(String oreKey) {
        pendingChunks.removeIf(pending -> pending.oreKey().equals(oreKey));
        queuedChunks.keySet().removeIf(key -> key.startsWith(oreKey + ":"));
        capWarningLogged = false;
    }

    boolean isEmpty() {
        return pendingChunks.isEmpty();
    }

    int size() {
        return pendingChunks.size();
    }

    boolean reachedCapWarning() {
        if (pendingChunks.size() >= maxQueueSize && !capWarningLogged) {
            capWarningLogged = true;
            return true;
        }
        if (pendingChunks.size() < maxQueueSize) {
            capWarningLogged = false;
        }
        return false;
    }

    private boolean capQueueIfNeeded(String addedKey) {
        while (pendingChunks.size() > maxQueueSize && !pendingChunks.isEmpty()) {
            PendingChunk dropped = pendingChunks.stream()
                    .max(Comparator.comparingInt(PendingChunk::distanceSquared).thenComparingLong(PendingChunk::sequence))
                    .orElse(null);
            if (dropped == null) {
                break;
            }
            pendingChunks.remove(dropped);
            queuedChunks.remove(dropped.key());
        }
        return queuedChunks.containsKey(addedKey);
    }
}
