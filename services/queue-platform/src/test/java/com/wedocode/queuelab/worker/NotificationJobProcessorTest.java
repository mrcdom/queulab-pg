package com.wedocode.queuelab.worker;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wedocode.queuelab.core.AppConfig;
import com.wedocode.queuelab.core.JsonSupport;
import com.wedocode.queuelab.core.QueueJob;
import com.wedocode.queuelab.core.QueueStatus;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class NotificationJobProcessorTest {

  @Test
  void processPreservesInterruptedFlagWhenSleepIsInterrupted() {
    var config = new AppConfig(
        "jdbc:postgresql://localhost:5432/queue_lab",
        "postgres",
        "admin",
        7070,
        1,
        1,
        10,
        10,
        30,
        10,
        100,
        100,
        false
    );
    var processor = new NotificationJobProcessor(config);

    Thread.currentThread().interrupt();
    try {
      assertThrows(IllegalStateException.class, () -> processor.process(validJob(1L, 1, 200)));
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void processThrowsPermanentWhenPayloadIsInvalid() {
    var config = new AppConfig(
        "jdbc:postgresql://localhost:5432/queue_lab",
        "postgres",
        "admin",
        7070,
        1,
        1,
        10,
        10,
        30,
        10,
        100,
        100,
        false
    );
    var processor = new NotificationJobProcessor(config);

    var invalidPayload = JsonSupport.MAPPER.createObjectNode();
    invalidPayload.put("recipient", "user@example.com");

    assertThrows(PermanentProcessingException.class, () -> processor.process(jobWithPayload(2L, 0, invalidPayload)));
  }

  private static QueueJob validJob(long id, int attempts, int simulatedDurationMs) {
    var payload = JsonSupport.MAPPER.createObjectNode();
    payload.put("channel", "EMAIL");
    payload.put("recipient", "user@example.com");
    payload.put("simulatedDurationMs", simulatedDurationMs);
    return jobWithPayload(id, attempts, payload);
  }

  private static QueueJob jobWithPayload(long id, int attempts, ObjectNode payload) {
    var now = Instant.now();
    return new QueueJob(
        id,
        1L,
        "notification.send",
        null,
        payload,
        QueueStatus.PROCESSING,
        now,
        attempts,
        6,
        now,
        "worker-1",
        null,
        now,
        now
    );
  }
}
