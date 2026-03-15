package com.wedocode.queuelab.api;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.LongAdder;

public final class EventChannelMetrics {
  private final Instant startedAt = Instant.now();
  private final LongAdder publishedTotal = new LongAdder();
  private final LongAdder publishFailuresTotal = new LongAdder();

  public void recordPublished() {
    publishedTotal.increment();
  }

  public void recordPublishFailure() {
    publishFailuresTotal.increment();
  }

  public Snapshot snapshot(int activeWebSocketConnections) {
    var now = Instant.now();
    var uptimeSeconds = Math.max(Duration.between(startedAt, now).toMillis() / 1000.0, 1.0);
    var published = publishedTotal.sum();
    return new Snapshot(
        published,
        publishFailuresTotal.sum(),
        published / uptimeSeconds,
        activeWebSocketConnections,
        startedAt,
        now
    );
  }

  public record Snapshot(
      long publishedTotal,
      long publishFailuresTotal,
      double publishedRatePerSecond,
      int activeWebSocketConnections,
      Instant startedAt,
      Instant now
  ) {
  }
}
