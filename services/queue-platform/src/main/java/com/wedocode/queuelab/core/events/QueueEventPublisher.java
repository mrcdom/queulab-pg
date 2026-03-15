package com.wedocode.queuelab.core.events;

public interface QueueEventPublisher {
  void publish(OutboxEvent event) throws Exception;
}
