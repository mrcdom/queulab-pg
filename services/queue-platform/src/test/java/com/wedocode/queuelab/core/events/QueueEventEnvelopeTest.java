package com.wedocode.queuelab.core.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.wedocode.queuelab.core.JsonSupport;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QueueEventEnvelopeTest {
  @Test
  void shouldSerializeEnvelopeWithAllRequiredFields() {
    var payload = JsonNodeFactory.instance.objectNode();
    payload.put("attempt", 1);
    payload.put("workerId", "worker-a");

    var envelope = QueueEventEnvelope.of(
        QueueEventType.JOB_CREATED,
        UUID.randomUUID().toString(),
        Instant.parse("2026-03-15T14:20:10.123Z"),
        "queue-platform",
        "corr-123",
        "notifications.email",
        42L,
        7L,
        payload
    );

    var node = JsonSupport.MAPPER.valueToTree(envelope);
    assertEquals(envelope.eventId(), node.get("eventId").asText());
    assertEquals("job.created", node.get("eventType").asText());
    assertEquals(1, node.get("eventVersion").asInt());
    assertEquals("2026-03-15T14:20:10.123Z", node.get("occurredAt").asText());
    assertEquals("queue-platform", node.get("source").asText());
    assertEquals("corr-123", node.get("correlationId").asText());
    assertEquals("notifications.email", node.get("queueName").asText());
    assertEquals(42L, node.get("jobId").asLong());
    assertEquals(7L, node.get("jobVersion").asLong());
    assertEquals(1, node.get("payload").get("attempt").asInt());
  }

  @Test
  void shouldRejectInvalidRequiredFields() {
    var now = Instant.parse("2026-03-15T14:20:10.123Z");
    var payload = JsonNodeFactory.instance.objectNode();

    assertThrows(IllegalArgumentException.class, () -> new QueueEventEnvelope(
        "",
        "job.created",
        1,
        now,
        "queue-platform",
        "corr-123",
        "notifications.email",
        1L,
        1L,
        payload
    ));

    assertThrows(IllegalArgumentException.class, () -> new QueueEventEnvelope(
        "evt-1",
        "job.created",
        0,
        now,
        "queue-platform",
        "corr-123",
        "notifications.email",
        1L,
        1L,
        payload
    ));

    assertThrows(IllegalArgumentException.class, () -> new QueueEventEnvelope(
        "evt-1",
        "job.created",
        1,
        now,
        "queue-platform",
        "corr-123",
        "notifications.email",
        0L,
        1L,
        payload
    ));

    assertThrows(IllegalArgumentException.class, () -> new QueueEventEnvelope(
        "evt-1",
        "job.created",
        1,
        now,
        "queue-platform",
        "corr-123",
        "notifications.email",
        1L,
        0L,
        payload
    ));
  }
}
