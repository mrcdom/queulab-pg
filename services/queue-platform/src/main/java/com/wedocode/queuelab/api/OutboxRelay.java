package com.wedocode.queuelab.api;

import com.wedocode.queuelab.core.JsonSupport;
import com.wedocode.queuelab.core.QueueRepository;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class OutboxRelay {
  private static final Logger LOGGER = LoggerFactory.getLogger(OutboxRelay.class);
  private final QueueRepository repository;
  private final QueueEventHub eventHub;
  private final EventChannelMetrics metrics;
  private final ScheduledExecutorService scheduler;
  private volatile boolean started;

  public OutboxRelay(QueueRepository repository, QueueEventHub eventHub, EventChannelMetrics metrics) {
    this.repository = repository;
    this.eventHub = eventHub;
    this.metrics = metrics;
    this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
      var thread = new Thread(runnable, "outbox-relay");
      thread.setDaemon(true);
      return thread;
    });
  }

  public synchronized void start() {
    if (started) {
      return;
    }
    started = true;
    scheduler.scheduleWithFixedDelay(this::relayBatch, 100, 250, TimeUnit.MILLISECONDS);
  }

  public synchronized void stop() {
    if (!started) {
      return;
    }
    started = false;
    scheduler.shutdownNow();
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
