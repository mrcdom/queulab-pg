package com.wedocode.queuelab.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public final class QueueService {
  private final QueueRepository repository;
  private final AppConfig config;

  public QueueService(QueueRepository repository, AppConfig config) {
    this.repository = repository;
    this.config = config;
  }

  public QueueRepository.EnqueueResult enqueue(EnqueueCommand command) {
    validate(command);
    return repository.enqueue(command);
  }

  public DashboardSnapshot dashboard() {
    return repository.fetchDashboard();
  }

  public List<QueueJob> jobs(Optional<String> queueName, Optional<String> status, Optional<String> search, int limit) {
    var parsedStatus = status.map(value -> QueueStatus.valueOf(value.toUpperCase(Locale.ROOT)));
    return repository.listJobs(queueName, parsedStatus, search, Math.min(Math.max(limit, 1), 500));
  }

  public Optional<QueueJob> findJob(long jobId) {
    return repository.findJobById(jobId);
  }

  public List<WorkerSnapshot> workers() {
    return repository.listWorkers();
  }

  public boolean requeueFailedJob(long jobId) {
    return repository.requeueJob(jobId);
  }

  public int reconcileNow() {
    return repository.reconcileStuckJobs(Duration.ofSeconds(config.processingTimeoutSeconds()));
  }

  public BurstResult enqueueBurst(BurstCommand command) {
    if (command.count() <= 0 || command.count() > 500) {
      throw new IllegalArgumentException("count deve ficar entre 1 e 500");
    }

    int created = 0;
    int deduplicated = 0;
    for (int index = 0; index < command.count(); index++) {
      var payload = createPayloadFromBurst(command, index);
      var dedupKey = command.useDeduplication()
          ? command.dedupPrefix() + ":" + (command.repeatDedupKey() ? 0 : index)
          : null;

      var result = enqueue(new EnqueueCommand(
          command.queueName(),
          dedupKey,
          payload,
          command.availableAt(),
          command.maxAttempts()
      ));

      if (result.created()) {
        created++;
      } else {
        deduplicated++;
      }
    }

    return new BurstResult(created, deduplicated, command.count());
  }

  public BurstResult enqueueScenario(String scenarioName) {
    var normalized = scenarioName.toLowerCase(Locale.ROOT);
    return switch (normalized) {
      case "happy-path" -> enqueueBurst(new BurstCommand("notification.send", 20, 0, 0, false, false, "happy-path", null, 6));
      case "transient-failures" -> enqueueBurst(new BurstCommand("notification.send", 18, 2, 0, false, false, "transient", null, 6));
      case "permanent-failures" -> enqueueBurst(new BurstCommand("notification.send", 12, 0, 12, false, false, "permanent", null, 3));
      case "duplicate-messages" -> enqueueBurst(new BurstCommand("notification.send", 10, 0, 0, true, true, "duplicate", null, 6));
      case "scheduled-jobs" -> enqueueBurst(new BurstCommand("notification.send", 16, 0, 0, false, false, "scheduled", Instant.now().plusSeconds(45), 6));
      default -> throw new IllegalArgumentException("Cenario desconhecido: " + scenarioName);
    };
  }

  private JsonNode createPayloadFromBurst(BurstCommand command, int index) {
    var payload = JsonNodeFactory.instance.objectNode();
    payload.put("notificationId", UUID.randomUUID().toString());
    payload.put("channel", pickChannel(index));
    payload.put("recipient", "demo+" + index + "@queue-lab.local");
    payload.put("template", pickTemplate(index));
    payload.put("message", "Mensagem demonstrativa " + index);
    payload.put("transientFailuresBeforeSuccess", index < command.transientFailuresBeforeSuccess() ? command.transientFailuresBeforeSuccess() : 0);
    payload.put("forcePermanentFailure", index < command.permanentFailures());
    payload.put("simulatedDurationMs", config.simulatedMinLatencyMillis() + (index * 73 % Math.max(config.simulatedMaxLatencyMillis() - config.simulatedMinLatencyMillis(), 1)));
    payload.put("source", "simulator");
    payload.put("createdByScenario", command.dedupPrefix());
    return payload;
  }

  private String pickChannel(int index) {
    return switch (index % 4) {
      case 0 -> "EMAIL";
      case 1 -> "WHATSAPP";
      case 2 -> "PUSH";
      default -> "SMS";
    };
  }

  private String pickTemplate(int index) {
    return switch (index % 3) {
      case 0 -> "confirmation";
      case 1 -> "appointment-reminder";
      default -> "billing-warning";
    };
  }

  private void validate(EnqueueCommand command) {
    if (command.queueName() == null || command.queueName().isBlank()) {
      throw new IllegalArgumentException("queueName e obrigatorio");
    }
    if (command.payload() == null || command.payload().isNull()) {
      throw new IllegalArgumentException("payload e obrigatorio");
    }
    if (command.maxAttempts() < 1 || command.maxAttempts() > 20) {
      throw new IllegalArgumentException("maxAttempts deve ficar entre 1 e 20");
    }
  }

  public record EnqueueCommand(
      String queueName,
      String dedupKey,
      JsonNode payload,
      Instant availableAt,
      int maxAttempts
  ) {
  }

  public record BurstCommand(
      String queueName,
      int count,
      int transientFailuresBeforeSuccess,
      int permanentFailures,
      boolean useDeduplication,
      boolean repeatDedupKey,
      String dedupPrefix,
      Instant availableAt,
      int maxAttempts
  ) {
  }

  public record BurstResult(int created, int deduplicated, int requested) {
  }

  public record MutationResponse(boolean success, String message) {
  }
}