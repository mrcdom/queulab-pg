package com.wedocode.queuelab.core;

import java.time.Instant;

public record WorkerSnapshot(
    String workerId,
    Instant startedAt,
    Instant lastHeartbeatAt,
    String status,
    long processedCount,
    long failedCount
) {
}