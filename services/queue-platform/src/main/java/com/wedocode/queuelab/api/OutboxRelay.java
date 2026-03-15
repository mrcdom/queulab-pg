package com.wedocode.queuelab.api;

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
  private final ScheduledExecutorService scheduler;
  private volatile boolean started;

  public OutboxRelay(QueueRepository repository, QueueEventHub eventHub) {
    this.repository = repository;
    this.eventHub = eventHub;
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
          eventHub.broadcast(event.payload());
          repository.markOutboxSent(event.outboxId());
        } catch (Exception exception) {
          repository.markOutboxFailed(event.outboxId(), exception.getMessage());
        }
      }
    } catch (Exception exception) {
      LOGGER.warn("Falha no relay de eventos da outbox", exception);
    }
  }
}
