package com.wedocode.queuelab.core.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import java.time.Instant;
import java.util.Objects;

public record QueueEventEnvelope(
    String eventId,
    String eventType,
    int eventVersion,
    Instant occurredAt,
    String source,
    String correlationId,
    String queueName,
    long jobId,
    long jobVersion,
    JsonNode payload
) {
  public static final int CURRENT_EVENT_VERSION = 1;

  public QueueEventEnvelope {
    requireNotBlank(eventId, "eventId");
    requireNotBlank(eventType, "eventType");
    if (eventVersion < 1) {
      throw new IllegalArgumentException("eventVersion deve ser maior ou igual a 1");
    }
    occurredAt = Objects.requireNonNull(occurredAt, "occurredAt e obrigatorio");
    requireNotBlank(source, "source");
    requireNotBlank(correlationId, "correlationId");
    requireNotBlank(queueName, "queueName");
    if (jobId <= 0) {
      throw new IllegalArgumentException("jobId deve ser maior que zero");
    }
    if (jobVersion <= 0) {
      throw new IllegalArgumentException("jobVersion deve ser maior que zero");
    }
    payload = payload == null ? NullNode.instance : payload;
  }

  public static QueueEventEnvelope of(
      QueueEventType eventType,
      String eventId,
      Instant occurredAt,
      String source,
      String correlationId,
      String queueName,
      long jobId,
      long jobVersion,
      JsonNode payload
  ) {
    return new QueueEventEnvelope(
        eventId,
        Objects.requireNonNull(eventType, "eventType e obrigatorio").wireName(),
        CURRENT_EVENT_VERSION,
        occurredAt,
        source,
        correlationId,
        queueName,
        jobId,
        jobVersion,
        payload
    );
  }

  private static void requireNotBlank(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " e obrigatorio");
    }
  }
}
