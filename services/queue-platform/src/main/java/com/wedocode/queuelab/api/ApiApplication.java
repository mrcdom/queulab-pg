package com.wedocode.queuelab.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.wedocode.queuelab.core.AppConfig;
import com.wedocode.queuelab.core.DataSourceFactory;
import com.wedocode.queuelab.core.QueueService;
import com.wedocode.queuelab.core.QueueRepository;
import com.wedocode.queuelab.worker.NotificationJobProcessor;
import com.wedocode.queuelab.worker.QueueRepositoryWorkerAdapter;
import com.wedocode.queuelab.worker.WorkerRuntime;
import io.javalin.Javalin;
import io.javalin.http.HttpStatus;
import io.javalin.json.JavalinJackson;
import java.time.Instant;
import java.util.Map;

public final class ApiApplication {
  private ApiApplication() {
  }

  public static void main(String[] args) {
    var config = AppConfig.fromEnv();
    var dataSource = DataSourceFactory.create(config);
    var repository = new QueueRepository(dataSource);
    var service = new QueueService(repository, config);
    var eventHub = new QueueEventHub();
    var eventMetrics = new EventChannelMetrics();
    var outboxRelay = new OutboxRelay(repository, eventHub, eventMetrics);
    outboxRelay.start();

    WorkerRuntime embeddedRuntime = null;
    if (config.startEmbeddedWorkers()) {
      embeddedRuntime = new WorkerRuntime(dataSource, new QueueRepositoryWorkerAdapter(repository), config, new NotificationJobProcessor(config));
      embeddedRuntime.start();
    }

    var runtimeReference = embeddedRuntime;
    var app = Javalin.create(configuration -> {
      configuration.jsonMapper(new JavalinJackson());
      configuration.showJavalinBanner = false;
      configuration.http.defaultContentType = "application/json";
      configuration.bundledPlugins.enableCors(cors -> cors.addRule(rule -> {
        rule.anyHost();
      }));
    });

    app.get("/api/health", ctx -> ctx.json(Map.of("status", "ok", "timestamp", Instant.now())));

    app.get("/api/dashboard", ctx -> ctx.json(service.dashboard()));
    app.get("/api/dashboard/snapshot", ctx -> ctx.json(service.dashboard()));
    app.get("/api/jobs", ctx -> ctx.json(service.jobs(
        optional(ctx.queryParam("queueName")),
        optional(ctx.queryParam("status")),
        optional(ctx.queryParam("search")),
        ctx.queryParamAsClass("limit", Integer.class).getOrDefault(100)
    )));
    app.get("/api/jobs/{id}", ctx -> service.findJob(ctx.pathParamAsClass("id", Long.class).get())
        .ifPresentOrElse(ctx::json, () -> ctx.status(HttpStatus.NOT_FOUND).json(Map.of("message", "Job nao encontrado"))));
    app.get("/api/dlq", ctx -> ctx.json(service.jobs(optional(ctx.queryParam("queueName")), optional("FAILED"), optional(ctx.queryParam("search")), 100)));
    app.get("/api/workers", ctx -> ctx.json(service.workers()));
    app.get("/api/observability/events", ctx -> ctx.json(eventMetrics.snapshot(eventHub.activeConnections())));
    app.get("/api/events/since", ctx -> {
      var cursor = ctx.queryParamAsClass("cursor", Long.class).getOrDefault(0L);
      var limit = Math.min(Math.max(ctx.queryParamAsClass("limit", Integer.class).getOrDefault(100), 1), 500);
      ctx.json(repository.listEventsSince(cursor, limit));
    });

    app.ws("/api/events/ws", ws -> {
      ws.onConnect(eventHub::register);
      ws.onClose(eventHub::unregister);
      ws.onError(eventHub::unregister);
    });

    app.post("/api/jobs", ctx -> {
      var request = ctx.bodyAsClass(EnqueueRequest.class);
      var result = service.enqueue(new QueueService.EnqueueCommand(
          request.queueName(),
          request.dedupKey(),
          request.payload(),
          request.availableAt(),
          request.maxAttempts() == null ? 6 : request.maxAttempts()
      ));
      ctx.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
          .json(Map.of("jobId", result.jobId(), "created", result.created()));
    });

    app.post("/api/jobs/{id}/requeue", ctx -> {
      boolean success = service.requeueFailedJob(ctx.pathParamAsClass("id", Long.class).get());
      if (!success) {
        ctx.status(HttpStatus.BAD_REQUEST).json(new QueueService.MutationResponse(false, "Somente jobs em FAILED podem ser reenfileirados"));
        return;
      }
      ctx.json(new QueueService.MutationResponse(true, "Job reenfileirado"));
    });

    app.post("/api/admin/reconcile", ctx -> ctx.json(Map.of("recovered", service.reconcileNow())));

    app.post("/api/simulator/burst", ctx -> {
      var request = ctx.bodyAsClass(BurstRequest.class);
      var result = service.enqueueBurst(new QueueService.BurstCommand(
          request.queueName() == null || request.queueName().isBlank() ? "notification.send" : request.queueName(),
          request.count() == null ? 10 : request.count(),
          request.transientFailuresBeforeSuccess() == null ? 0 : request.transientFailuresBeforeSuccess(),
          request.permanentFailures() == null ? 0 : request.permanentFailures(),
          request.useDeduplication() != null && request.useDeduplication(),
          request.repeatDedupKey() != null && request.repeatDedupKey(),
          request.dedupPrefix() == null || request.dedupPrefix().isBlank() ? "burst" : request.dedupPrefix(),
          request.availableAt(),
          request.maxAttempts() == null ? 6 : request.maxAttempts()
      ));
      ctx.status(HttpStatus.CREATED).json(result);
    });

    app.post("/api/simulator/scenarios/{scenario}", ctx -> {
      var result = service.enqueueScenario(ctx.pathParam("scenario"));
      ctx.status(HttpStatus.CREATED).json(result);
    });

    app.exception(IllegalArgumentException.class, (exception, ctx) -> {
      ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("message", exception.getMessage()));
    });
    app.exception(Exception.class, (exception, ctx) -> {
      ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(Map.of("message", exception.getMessage()));
    });

    Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().name("api-shutdown").unstarted(() -> {
      outboxRelay.stop();
      if (runtimeReference != null) {
        runtimeReference.stop();
      }
    }));

    app.start(config.apiPort());
  }

  private static java.util.Optional<String> optional(String value) {
    return value == null || value.isBlank() ? java.util.Optional.empty() : java.util.Optional.of(value);
  }

  public record EnqueueRequest(
      String queueName,
      String dedupKey,
      JsonNode payload,
      Instant availableAt,
      Integer maxAttempts
  ) {
  }

  public record BurstRequest(
      String queueName,
      Integer count,
      Integer transientFailuresBeforeSuccess,
      Integer permanentFailures,
      Boolean useDeduplication,
      Boolean repeatDedupKey,
      String dedupPrefix,
      Instant availableAt,
      Integer maxAttempts
  ) {
  }
}