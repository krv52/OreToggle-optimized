package org.example;

record ChunkScanResult(int blockChecks, int removedThisTick, boolean finished, boolean completed) {
}
