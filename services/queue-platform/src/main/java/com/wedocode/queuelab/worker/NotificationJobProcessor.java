package com.wedocode.queuelab.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.wedocode.queuelab.core.AppConfig;
import com.wedocode.queuelab.core.QueueJob;

public final class NotificationJobProcessor implements JobProcessor {
  private final AppConfig config;

  public NotificationJobProcessor(AppConfig config) {
    this.config = config;
  }

  @Override
  public void process(QueueJob job) throws TransientProcessingException, PermanentProcessingException {
    JsonNode payload = job.payload();
    String channel = text(payload, "channel");
    String recipient = text(payload, "recipient");

    if (channel == null || recipient == null) {
      throw new PermanentProcessingException("Payload invalido: channel e recipient sao obrigatorios");
    }

    sleepFor(payload.path("simulatedDurationMs").asInt(defaultLatency()));

    if (payload.path("forcePermanentFailure").asBoolean(false)) {
      throw new PermanentProcessingException("Falha permanente simulada para o canal " + channel);
    }

    int transientFailuresBeforeSuccess = payload.path("transientFailuresBeforeSuccess").asInt(0);
    if (job.attempts() < transientFailuresBeforeSuccess) {
      throw new TransientProcessingException("Falha transitoria simulada para " + recipient + " na tentativa " + (job.attempts() + 1));
    }
  }

  private int defaultLatency() {
    return Math.max(config.simulatedMinLatencyMillis(), 100);
  }

  private void sleepFor(int milliseconds) {
    try {
      Thread.sleep(Math.max(milliseconds, 25));
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Thread interrompida durante o processamento simulado", exception);
    }
  }

  private String text(JsonNode payload, String fieldName) {
    JsonNode node = payload.get(fieldName);
    if (node == null || node.isNull() || node.asText().isBlank()) {
      return null;
    }
    return node.asText();
  }
}