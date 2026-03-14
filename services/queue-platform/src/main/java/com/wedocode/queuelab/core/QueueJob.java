package com.wedocode.queuelab.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

public record QueueJob(
    long id,
    String queueName,
    String dedupKey,
    JsonNode payload,
    QueueStatus status,
    Instant availableAt,
    int attempts,
    int maxAttempts,
    Instant lockedAt,
    String lockedBy,
    String lastError,
    Instant createdAt,
    Instant updatedAt
) {
}