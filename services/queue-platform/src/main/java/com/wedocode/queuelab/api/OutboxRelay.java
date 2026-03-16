package com.wedocode.queuelab.api;

import com.wedocode.queuelab.core.JsonSupport;
import com.wedocode.queuelab.core.QueueRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class OutboxRelay {
  private static final long INITIAL_DELAY_MILLIS = 100L;
  private static final long RELAY_INTERVAL_MILLIS = 250L;
  private static final Logger LOGGER = LoggerFactory.getLogger(OutboxRelay.class);
  private final QueueRepository repository;
  private final QueueEventHub eventHub;
  private final EventChannelMetrics metrics;
  private volatile boolean started;
  private Thread relayThread;

  public OutboxRelay(QueueRepository repository, QueueEventHub eventHub, EventChannelMetrics metrics) {
    this.repository = repository;
    this.eventHub = eventHub;
    this.metrics = metrics;
  }

  public synchronized void start() {
    if (started) {
      return;
    }
    started = true;
    relayThread = Thread.ofVirtual().name("outbox-relay").start(this::runRelayLoop);
  }

  public synchronized void stop() {
    if (!started) {
      return;
    }
    started = false;
    if (relayThread != null) {
      relayThread.interrupt();
    }
  }

  private void runRelayLoop() {
    if (!sleepInterruptibly(INITIAL_DELAY_MILLIS)) {
      return;
    }
    while (started) {
      relayBatch();
      if (!sleepInterruptibly(RELAY_INTERVAL_MILLIS)) {
        return;
      }
    }
  }

  private boolean sleepInterruptibly(long milliseconds) {
    try {
      Thread.sleep(milliseconds);
      return true;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private void relayBatch() {
    try {
      var events = repository.claimPendingOutboxEvents(100);
      for (var event : events) {
        try {
          eventHub.broadcast(toStreamPayload(event.outboxId(), event.payload()));
          repository.markOutboxSent(event.outboxId());
          metrics.recordPublished();
        } catch (Exception exception) {
          repository.markOutboxFailed(event.outboxId(), exception.getMessage());
          metrics.recordPublishFailure();
        }
      }
    } catch (Exception exception) {
      LOGGER.warn("Falha no relay de eventos da outbox", exception);
    }
  }

  private String toStreamPayload(long outboxId, String payload) throws Exception {
    var root = JsonSupport.MAPPER.createObjectNode();
    root.put("outboxId", outboxId);
    root.set("event", JsonSupport.MAPPER.readTree(payload));
    return JsonSupport.MAPPER.writeValueAsString(root);
  }
}
