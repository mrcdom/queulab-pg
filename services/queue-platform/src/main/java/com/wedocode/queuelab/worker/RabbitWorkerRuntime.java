package com.wedocode.queuelab.worker;

import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.wedocode.queuelab.core.AppConfig;
import com.wedocode.queuelab.core.JsonSupport;
import com.wedocode.queuelab.core.QueueRepository;
import com.wedocode.queuelab.core.QueueStatus;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RabbitWorkerRuntime {
  private static final Logger LOGGER = LoggerFactory.getLogger(RabbitWorkerRuntime.class);
  private final QueueRepository repository;
  private final AppConfig config;
  private final JobProcessor processor;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private final CopyOnWriteArrayList<Thread> workerThreads = new CopyOnWriteArrayList<>();
  private final CountDownLatch shutdownLatch = new CountDownLatch(1);
  private final String runtimeId = UUID.randomUUID().toString().substring(0, 8);

  public RabbitWorkerRuntime(QueueRepository repository, AppConfig config, JobProcessor processor) {
    this.repository = Objects.requireNonNull(repository, "repository nao pode ser nulo");
    this.config = Objects.requireNonNull(config, "config nao pode ser nulo");
    this.processor = Objects.requireNonNull(processor, "processor nao pode ser nulo");
  }

  public void start() {
    if (!running.compareAndSet(false, true)) {
      return;
    }

    LOGGER.info(
        "Iniciando RabbitWorkerRuntime com {} workers (exchange='{}', queue='{}', routingKey='{}', host='{}', port={})",
        config.workerThreads(),
        config.rabbitExchange(),
        config.rabbitQueue(),
        config.rabbitRoutingKey(),
        config.rabbitHost(),
        config.rabbitPort());

    for (int index = 0; index < config.workerThreads(); index++) {
      var workerId = "rabbit-worker-" + runtimeId + "-" + index;
      var thread = Thread.ofPlatform().name(workerId).start(() -> runWorkerLoop(workerId));
      workerThreads.add(thread);
    }
  }

  public void stop() {
    if (!running.compareAndSet(true, false)) {
      return;
    }

    for (var thread : workerThreads) {
      thread.interrupt();
    }
    shutdownLatch.countDown();
  }

  public void await() {
    try {
      shutdownLatch.await();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      stop();
    }
  }

  private void runWorkerLoop(String workerId) {
    repository.registerWorker(workerId);
    try {
      var factory = new ConnectionFactory();
      factory.setHost(config.rabbitHost());
      factory.setPort(config.rabbitPort());
      factory.setUsername(config.rabbitUsername());
      factory.setPassword(config.rabbitPassword());
      factory.setVirtualHost(config.rabbitVirtualHost());

      try (Connection connection = factory.newConnection("queue-platform-worker-" + workerId);
           var channel = connection.createChannel()) {
        channel.exchangeDeclare(config.rabbitExchange(), BuiltinExchangeType.DIRECT, true);
        channel.queueDeclare(config.rabbitQueue(), true, false, false, null);
        channel.queueBind(config.rabbitQueue(), config.rabbitExchange(), config.rabbitRoutingKey());
        channel.basicQos(config.rabbitPrefetch());

        LOGGER.info(
          "Worker {} conectado ao RabbitMQ (exchange='{}', queue='{}', routingKey='{}', prefetch={})",
          workerId,
          config.rabbitExchange(),
          config.rabbitQueue(),
          config.rabbitRoutingKey(),
          config.rabbitPrefetch());

        while (running.get()) {
          repository.heartbeatWorker(workerId);
          var delivery = channel.basicGet(config.rabbitQueue(), false);
          if (delivery == null) {
            sleepQuietly(config.idleSleepMillis());
            continue;
          }

          long deliveryTag = delivery.getEnvelope().getDeliveryTag();
          var correlationId = correlationId(workerId);
          var startedAt = Instant.now();

          try {
            var payload = JsonSupport.MAPPER.readTree(new String(delivery.getBody(), StandardCharsets.UTF_8));
            long jobId = payload.path("jobId").asLong(0);
            if (jobId <= 0) {
              LOGGER.warn("Worker {} recebeu mensagem sem jobId valido; descartando (deliveryTag={})", workerId, deliveryTag);
              channel.basicAck(deliveryTag, false);
              continue;
            }

            var maybeJob = repository.markJobProcessingFromBroker(jobId, workerId, delivery.getProps().getMessageId(), correlationId);
            if (maybeJob.isEmpty()) {
              LOGGER.debug("Worker {} recebeu job {} nao elegivel para processamento; confirmando ack", workerId, jobId);
              channel.basicAck(deliveryTag, false);
              continue;
            }

            var job = maybeJob.get();
            try {
              processor.process(job);
              repository.markDone(job.id(), workerId, correlationId);
              repository.recordExecution(job.id(), workerId, job.attempts() + 1, "DONE", null, startedAt, Instant.now());
              repository.updateWorkerStats(workerId, true);
              LOGGER.debug("Worker {} concluiu job {} com sucesso", workerId, job.id());
              channel.basicAck(deliveryTag, false);
            } catch (TransientProcessingException exception) {
              var retryResult = repository.scheduleRetry(job.id(), workerId, exception.getMessage(), correlationId);
              repository.recordExecution(job.id(), workerId, retryResult.attempts(), "RETRY", exception.getMessage(), startedAt, Instant.now());
              repository.updateWorkerStats(workerId, false);
              if (retryResult.status() == QueueStatus.RETRY) {
                repository.enqueueBrokerMessage(job.id(), job.queueName(), retryResult.availableAt(), correlationId);
                LOGGER.debug("Worker {} reagendou job {} para retry (status={}, availableAt={})", workerId, job.id(), retryResult.status(), retryResult.availableAt());
              }
              channel.basicAck(deliveryTag, false);
            } catch (PermanentProcessingException exception) {
              repository.markFailed(job.id(), workerId, exception.getMessage(), correlationId);
              repository.recordExecution(job.id(), workerId, job.attempts() + 1, "FAILED", exception.getMessage(), startedAt, Instant.now());
              repository.updateWorkerStats(workerId, false);
              LOGGER.warn("Worker {} marcou job {} como FAILED: {}", workerId, job.id(), exception.getMessage());
              channel.basicReject(deliveryTag, false);
            } catch (RuntimeException exception) {
              var retryResult = repository.scheduleRetry(job.id(), workerId, "Erro inesperado: " + exception.getMessage(), correlationId);
              repository.recordExecution(job.id(), workerId, retryResult.attempts(), "RETRY", exception.getMessage(), startedAt, Instant.now());
              repository.updateWorkerStats(workerId, false);
              if (retryResult.status() == QueueStatus.RETRY) {
                repository.enqueueBrokerMessage(job.id(), job.queueName(), retryResult.availableAt(), correlationId);
                LOGGER.debug("Worker {} reagendou job {} por erro inesperado (status={}, availableAt={})", workerId, job.id(), retryResult.status(), retryResult.availableAt());
              }
              channel.basicAck(deliveryTag, false);
            }
          } catch (Exception exception) {
            LOGGER.warn("Falha no consumo RabbitMQ, mensagem sera reencaminhada", exception);
            channel.basicNack(deliveryTag, false, true);
          }
        }
      }
    } catch (Exception exception) {
      if (running.get()) {
        LOGGER.error("Falha no worker RabbitMQ {}", workerId, exception);
      }
    } finally {
      repository.markWorkerStopped(workerId);
    }
  }

  private String correlationId(String workerId) {
    return "rabbit:" + workerId + ":" + UUID.randomUUID();
  }

  private void sleepQuietly(long millis) {
    try {
      Thread.sleep(Duration.ofMillis(millis));
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    }
  }
}
