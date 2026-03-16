package com.wedocode.queuelab.worker;

import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.wedocode.queuelab.core.AppConfig;
import com.wedocode.queuelab.core.QueueRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RabbitMqOutboxPublisher {
  private static final Logger LOGGER = LoggerFactory.getLogger(RabbitMqOutboxPublisher.class);
  private final DataSource dataSource;
  private final QueueRepository repository;
  private final AppConfig config;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private final Object wakeSignal = new Object();
  private Thread listenerThread;
  private Thread publisherThread;
  private Connection rabbitConnection;

  public RabbitMqOutboxPublisher(DataSource dataSource, QueueRepository repository, AppConfig config) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource nao pode ser nulo");
    this.repository = Objects.requireNonNull(repository, "repository nao pode ser nulo");
    this.config = Objects.requireNonNull(config, "config nao pode ser nulo");
  }

  public void start() {
    if (!running.compareAndSet(false, true)) {
      return;
    }

    LOGGER.info(
      "Iniciando RabbitMqOutboxPublisher (host='{}', port={}, exchange='{}', queue='{}', routingKey='{}', batchSize={})",
        config.rabbitHost(),
        config.rabbitPort(),
        config.rabbitExchange(),
        config.rabbitQueue(),
        config.rabbitRoutingKey(),
        config.brokerPublisherBatchSize());

    listenerThread = Thread.ofVirtual().name("broker-publish-listener").start(this::listenForNotifications);
    publisherThread = Thread.ofVirtual().name("broker-outbox-publisher").start(this::publishLoop);
  }

  public void stop() {
    if (!running.compareAndSet(true, false)) {
      return;
    }

    if (listenerThread != null) {
      listenerThread.interrupt();
    }
    if (publisherThread != null) {
      publisherThread.interrupt();
    }

    synchronized (wakeSignal) {
      wakeSignal.notifyAll();
    }

    closeRabbitConnection();
  }

  private void listenForNotifications() {
    while (running.get()) {
      try (var connection = dataSource.getConnection();
           var statement = connection.createStatement()) {
        statement.execute("LISTEN queue_publish");
        var pgConnection = connection.unwrap(PGConnection.class);
        while (running.get()) {
          var notifications = pgConnection.getNotifications(config.fallbackPollMillis());
          if (notifications != null && notifications.length > 0) {
            wakePublisher();
          }
        }
      } catch (Exception exception) {
        if (!running.get()) {
          return;
        }
        LOGGER.warn("Falha no listener queue_publish, mantendo fallback por polling", exception);
        sleepQuietly(1500);
      }
    }
  }

  private void publishLoop() {
    while (running.get()) {
      try {
        var messages = repository.claimPendingMessageOutbox(config.brokerPublisherBatchSize());
        if (messages.isEmpty()) {
          waitForSignal();
          continue;
        }

        LOGGER.debug("Publisher reservou {} mensagens da message_outbox", messages.size());

        try (var channel = ensureRabbitConnection().createChannel()) {
          channel.confirmSelect();
          channel.exchangeDeclare(config.rabbitExchange(), BuiltinExchangeType.DIRECT, true);
          for (var message : messages) {
            try {
              LOGGER.debug(
                  "Publicando mensagem outboxId={} jobId={} exchange='{}' routingKey='{}'",
                  message.outboxId(),
                  message.jobId(),
                  message.exchangeName(),
                  message.routingKey());
              channel.basicPublish(
                  message.exchangeName(),
                  message.routingKey(),
                  true,
                  new com.rabbitmq.client.AMQP.BasicProperties.Builder()
                      .messageId(message.messageId())
                      .contentType("application/json")
                      .deliveryMode(2)
                      .build(),
                  message.payload().getBytes(StandardCharsets.UTF_8)
              );
              channel.waitForConfirmsOrDie(5000);
              repository.markMessageOutboxSent(message.outboxId());
              LOGGER.debug("Mensagem outboxId={} confirmada pelo broker", message.outboxId());
            } catch (Exception publishException) {
              repository.markMessageOutboxFailed(message.outboxId(), publishException.getMessage());
              LOGGER.warn(
                  "Falha ao publicar outboxId={} jobId={} exchange='{}' routingKey='{}': {}",
                  message.outboxId(),
                  message.jobId(),
                  message.exchangeName(),
                  message.routingKey(),
                  publishException.getMessage());
            }
          }
        }
      } catch (Exception exception) {
        if (!running.get()) {
          return;
        }
        LOGGER.warn("Falha no publisher da message_outbox", exception);
        closeRabbitConnection();
        sleepQuietly(1200);
      }
    }
  }

  private Connection ensureRabbitConnection() throws Exception {
    if (rabbitConnection != null && rabbitConnection.isOpen()) {
      return rabbitConnection;
    }

    synchronized (this) {
      if (rabbitConnection != null && rabbitConnection.isOpen()) {
        return rabbitConnection;
      }
      var factory = new ConnectionFactory();
      factory.setHost(config.rabbitHost());
      factory.setPort(config.rabbitPort());
      factory.setUsername(config.rabbitUsername());
      factory.setPassword(config.rabbitPassword());
      factory.setVirtualHost(config.rabbitVirtualHost());
      rabbitConnection = factory.newConnection("queue-platform-publisher");
      LOGGER.info("Publisher conectado ao RabbitMQ em {}:{} (vhost='{}')", config.rabbitHost(), config.rabbitPort(), config.rabbitVirtualHost());
      return rabbitConnection;
    }
  }

  private void closeRabbitConnection() {
    try {
      if (rabbitConnection != null) {
        rabbitConnection.close();
      }
    } catch (Exception ignored) {
      // Best-effort close.
    } finally {
      rabbitConnection = null;
    }
  }

  private void wakePublisher() {
    synchronized (wakeSignal) {
      wakeSignal.notifyAll();
    }
  }

  private void waitForSignal() {
    synchronized (wakeSignal) {
      try {
        wakeSignal.wait(config.fallbackPollMillis());
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private void sleepQuietly(long millis) {
    try {
      Thread.sleep(Duration.ofMillis(millis));
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    }
  }
}
