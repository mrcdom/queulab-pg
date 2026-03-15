package com.wedocode.queuelab.core.events;

import java.time.Instant;

public record OutboxEvent(
    long outboxId,
    String eventId,
    long aggregateId,
    long aggregateVersion,
    Instant occurredAt,
    String payload
) {
}
