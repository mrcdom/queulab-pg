package com.wedocode.queuelab.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.wedocode.queuelab.core.events.OutboxEvent;
import com.wedocode.queuelab.core.events.QueueEventEnvelope;
import com.wedocode.queuelab.core.events.QueueEventType;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.postgresql.util.PGobject;

public final class QueueRepository {
  private final DataSource dataSource;
  private static final String DEFAULT_EXCHANGE = env("QUEUE_RABBIT_EXCHANGE", "jobs.direct");

  public QueueRepository(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  public EnqueueResult enqueue(QueueService.EnqueueCommand command, String correlationId) {
    var insertSql = """
        INSERT INTO job_queue (queue_name, dedup_key, payload, available_at, max_attempts, job_version)
        VALUES (?, ?, ?, COALESCE(?, NOW()), ?, ?)
        ON CONFLICT DO NOTHING
        RETURNING id, queue_name, job_version
        """;

    try (var connection = dataSource.getConnection();
         var statement = connection.prepareStatement(insertSql)) {
      connection.setAutoCommit(false);
      statement.setString(1, command.queueName());
      if (command.dedupKey() == null || command.dedupKey().isBlank()) {
        statement.setNull(2, Types.VARCHAR);
      } else {
        statement.setString(2, command.dedupKey());
      }
      statement.setObject(3, jsonb(command.payload()));
      if (command.availableAt() == null) {
        statement.setNull(4, Types.TIMESTAMP_WITH_TIMEZONE);
      } else {
        statement.setTimestamp(4, Timestamp.from(command.availableAt()));
      }
      statement.setInt(5, command.maxAttempts());
      statement.setLong(6, 1L);

      try (var resultSet = statement.executeQuery()) {
        if (resultSet.next()) {
          var jobId = resultSet.getLong("id");
          var queueName = resultSet.getString("queue_name");
          var jobVersion = resultSet.getLong("job_version");
          var payload = JsonNodeFactory.instance.objectNode();
          payload.put("created", true);
          insertOutboxEvent(connection, QueueEventEnvelope.of(
              QueueEventType.JOB_CREATED,
              UUID.randomUUID().toString(),
              Instant.now(),
              "queue-platform",
              correlationId,
              queueName,
              jobId,
              jobVersion,
              payload
          ));
          insertMessageOutbox(connection, jobId, queueName, correlationId, Instant.now());
          notifyQueuePublish(connection, queueName);
          connection.commit();
          connection.setAutoCommit(true);
          return new EnqueueResult(jobId, true);
        }
      }
      connection.commit();
      connection.setAutoCommit(true);
    } catch (SQLException exception) {
      throw new IllegalStateException("Nao foi possivel enfileirar o job", exception);
    }

    if (command.dedupKey() == null || command.dedupKey().isBlank()) {
      throw new IllegalStateException("Falha ao enfileirar job sem dedup_key e sem retorno do insert");
    }

    var findExistingSql = """
        SELECT id
        FROM job_queue
        WHERE queue_name = ?
          AND dedup_key = ?
          AND status IN ('PENDING'::queue_status, 'PROCESSING'::queue_status, 'RETRY'::queue_status)
        ORDER BY id DESC
        LIMIT 1
        """;

        try (var connection = dataSource.getConnection();
          var statement = connection.prepareStatement(findExistingSql)) {
      statement.setString(1, command.queueName());
      statement.setString(2, command.dedupKey());

      try (var resultSet = statement.executeQuery()) {
        if (resultSet.next()) {
          return new EnqueueResult(resultSet.getLong("id"), false);
        }
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Nao foi possivel localizar o job deduplicado", exception);
    }

    throw new IllegalStateException("Falha ao enfileirar job e localizar deduplicacao");
  }

  public List<QueueJob> claimReadyJobs(String workerId, int limit, String correlationId) {
    var sql = """
        WITH candidates AS (
          SELECT id
          FROM job_queue
          WHERE status IN ('PENDING'::queue_status, 'RETRY'::queue_status)
            AND available_at <= NOW()
          ORDER BY available_at, id
          FOR UPDATE SKIP LOCKED
          LIMIT ?
        )
        UPDATE job_queue q
        SET status = 'PROCESSING'::queue_status,
            locked_at = NOW(),
            locked_by = ?,
            job_version = q.job_version + 1,
            updated_at = NOW()
        FROM candidates
        WHERE q.id = candidates.id
        RETURNING q.*
        """;

    try (var connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try (var statement = connection.prepareStatement(sql)) {
        statement.setInt(1, limit);
        statement.setString(2, workerId);
        var jobs = new ArrayList<QueueJob>();
        try (var resultSet = statement.executeQuery()) {
          while (resultSet.next()) {
            jobs.add(mapJob(resultSet));
          }
        }
        for (var job : jobs) {
          var payload = JsonNodeFactory.instance.objectNode();
          payload.put("workerId", workerId);
          payload.put("attempt", job.attempts() + 1);
          insertOutboxEvent(connection, QueueEventEnvelope.of(
              QueueEventType.JOB_CLAIMED,
              UUID.randomUUID().toString(),
              Instant.now(),
              "queue-platform",
              correlationId,
              job.queueName(),
              job.id(),
              job.jobVersion(),
              payload
          ));
        }
        connection.commit();
        return jobs;
      } catch (SQLException exception) {
        connection.rollback();
        throw exception;
      } finally {
        connection.setAutoCommit(true);
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Nao foi possivel executar o claim de jobs", exception);
    }
  }

  public QueueJob markDone(long jobId, String workerId, String correlationId) {
    var sql = """
        UPDATE job_queue
        SET status = 'DONE'::queue_status,
            locked_at = NULL,
            locked_by = NULL,
            job_version = job_version + 1,
            updated_at = NOW()
        WHERE id = ?
          AND status = 'PROCESSING'::queue_status
          AND locked_by = ?
        RETURNING *
        """;

    try (var connection = dataSource.getConnection();
         var statement = connection.prepareStatement(sql)) {
      connection.setAutoCommit(false);
      statement.setLong(1, jobId);
      statement.setString(2, workerId);
      try (var resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          throw new IllegalStateException("Job nao encontrado para marcar DONE");
        }
        var updated = mapJob(resultSet);
        var payload = JsonNodeFactory.instance.objectNode();
        payload.put("workerId", workerId);
        insertOutboxEvent(connection, QueueEventEnvelope.of(
            QueueEventType.JOB_COMPLETED,
            UUID.randomUUID().toString(),
            Instant.now(),
            "queue-platform",
            correlationId,
            updated.queueName(),
            updated.id(),
            updated.jobVersion(),
            payload
        ));
        connection.commit();
        connection.setAutoCommit(true);
        return updated;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Nao foi possivel marcar job como DONE", exception);
    }
  }

  public RetryResult scheduleRetry(long jobId, String workerId, String errorMessage, String correlationId) {
    var sql = """
        UPDATE job_queue
        SET attempts = attempts + 1,
            status = CASE
              WHEN attempts + 1 >= max_attempts THEN 'FAILED'::queue_status
              ELSE 'RETRY'::queue_status
            END,
            available_at = CASE
              WHEN attempts + 1 >= max_attempts THEN available_at
              ELSE NOW() + make_interval(secs => LEAST((2 ^ (attempts + 1))::int, 900))
            END,
            last_error = ?,
            locked_at = NULL,
            locked_by = NULL,
            job_version = job_version + 1,
            updated_at = NOW()
        WHERE id = ?
          AND status = 'PROCESSING'::queue_status
          AND locked_by = ?
        RETURNING *
        """;

    try (var connection = dataSource.getConnection();
         var statement = connection.prepareStatement(sql)) {
      connection.setAutoCommit(false);
      statement.setString(1, errorMessage);
      statement.setLong(2, jobId);
      statement.setString(3, workerId);
      try (var resultSet = statement.executeQuery()) {
        if (resultSet.next()) {
          var updated = mapJob(resultSet);
          var payload = JsonNodeFactory.instance.objectNode();
          payload.put("workerId", workerId);
          payload.put("attempt", updated.attempts());
          payload.put("availableAt", updated.availableAt().toString());

          var eventType = updated.status() == QueueStatus.FAILED
              ? QueueEventType.JOB_FAILED
              : QueueEventType.JOB_RETRY_SCHEDULED;

          insertOutboxEvent(connection, QueueEventEnvelope.of(
              eventType,
              UUID.randomUUID().toString(),
              Instant.now(),
              "queue-platform",
              correlationId,
              updated.queueName(),
              updated.id(),
              updated.jobVersion(),
              payload
          ));

          connection.commit();
          connection.setAutoCommit(true);

          return new RetryResult(updated.status(), updated.attempts(), updated.availableAt(), updated);
        }
      }
      throw new IllegalStateException("Job nao encontrado para retry");
    } catch (SQLException exception) {
      throw new IllegalStateException("Nao foi possivel reagendar job", exception);
    }
  }

  public QueueJob markFailed(long jobId, String workerId, String errorMessage, String correlationId) {
    var sql = """
        UPDATE job_queue
        SET attempts = attempts + 1,
            status = 'FAILED'::queue_status,
            last_error = ?,
            locked_at = NULL,
            locked_by = NULL,
            job_version = job_version + 1,
            updated_at = NOW()
        WHERE id = ?
          AND status = 'PROCESSING'::queue_status
          AND locked_by = ?
        RETURNING *
        """;

    try (var connection = dataSource.getConnection();
         var statement = connection.prepareStatement(sql)) {
      connection.setAutoCommit(false);
      statement.setString(1, errorMessage);
      statement.setLong(2, jobId);
      statement.setString(3, workerId);
      try (var resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          throw new IllegalStateException("Job nao encontrado para marcar FAILED");
        }
        var updated = mapJob(resultSet);
        var payload = JsonNodeFactory.instance.objectNode();
        payload.put("workerId", workerId);
        payload.put("error", errorMessage == null ? "" : errorMessage);
        insertOutboxEvent(connection, QueueEventEnvelope.of(
            QueueEventType.JOB_FAILED,
            UUID.randomUUID().toString(),
            Instant.now(),
            "queue-platform",
            correlationId,
            updated.queueName(),
            updated.id(),
            updated.jobVersion(),
            payload
        ));
        connection.commit();
        connection.setAutoCommit(true);
        return updated;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Nao foi possivel marcar job como FAILED", exception);
    }
  }

  public int reconcileStuckJobs(Duration timeout, String correlationId) {
    var sql = """
        UPDATE job_queue
        SET status = 'RETRY'::queue_status,
            locked_at = NULL,
            locked_by = NULL,
            available_at = NOW(),
            job_version = job_version + 1,
            updated_at = NOW(),
            last_error = COALESCE(last_error, 'Reconciliado apos timeout de processamento')
        WHERE status = 'PROCESSING'::queue_status
          AND locked_at < NOW() - (? * INTERVAL '1 second')
        RETURNING *
        """;

    try (var connection = dataSource.getConnection();
         var statement = connection.prepareStatement(sql)) {
      connection.setAutoCommit(false);
      statement.setLong(1, timeout.getSeconds());
      var recovered = 0;
      try (var resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          var updated = mapJob(resultSet);
          var payload = JsonNodeFactory.instance.objectNode();
          payload.put("reason", "processing-timeout");
          insertOutboxEvent(connection, QueueEventEnvelope.of(
              QueueEventType.JOB_RECOVERED,
              UUID.randomUUID().toString(),
              Instant.now(),
              "queue-platform",
              correlationId,
              updated.queueName(),
              updated.id(),
              updated.jobVersion(),
              payload
          ));
          recovered++;
        }
      }
      connection.commit();
      connection.setAutoCommit(true);
      return recovered;
    } catch (SQLException exception) {
      throw new IllegalStateException("Nao foi possivel reconciliar jobs presos", exception);
    }
  }

  public DashboardSnapshot fetchDashboard() {
    var statusCounts = new EnumMap<QueueStatus, Long>(QueueStatus.class);
    for (QueueStatus status : QueueStatus.values()) {
      statusCounts.put(status, 0L);
    }

        var countsSql = "SELECT status, count(*) AS total FROM job_queue GROUP BY status";
        try (var connection = dataSource.getConnection();
          var statement = connection.createStatement();
          var resultSet = statement.executeQuery(countsSql)) {
      while (resultSet.next()) {
        statusCounts.put(QueueStatus.valueOf(resultSet.getString("status")), resultSet.getLong("total"));
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Nao foi possivel consultar os contadores do dashboard", exception);
    }

    var backlogs = new ArrayList<QueueBacklog>();
    var backlogSql = """
        SELECT queue_name,
         count(*) FILTER (WHERE status IN ('PENDING'::queue_status, 'RETRY'::queue_status) AND available_at <= NOW()) AS ready_jobs,
         count(*) FILTER (WHERE status = 'PROCESSING'::queue_status) AS processing_jobs,
         count(*) FILTER (WHERE status = 'FAILED'::queue_status) AS failed_jobs
        FROM job_queue
        GROUP BY queue_name
        ORDER BY queue_name
        """;
        try (var connection = dataSource.getConnection();
          var statement = connection.createStatement();
          var resultSet = statement.executeQuery(backlogSql)) {
      while (resultSet.next()) {
        backlogs.add(new QueueBacklog(
            resultSet.getString("queue_name"),
            resultSet.getLong("ready_jobs"),
            resultSet.getLong("processing_jobs"),
            resultSet.getLong("failed_jobs")
        ));
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Nao foi possivel consultar backlog por fila", exception);
    }

    return new DashboardSnapshot(
        statusCounts,
        backlogs,
        scalarDouble("SELECT COALESCE(EXTRACT(EPOCH FROM AVG(NOW() - created_at)), 0) FROM job_queue WHERE status IN ('PENDING'::queue_status, 'RETRY'::queue_status)"),
        scalarDouble("SELECT COALESCE(EXTRACT(EPOCH FROM AVG(finished_at - started_at)), 0) FROM job_execution_history"),
        scalarLong("SELECT count(*) FROM job_queue WHERE status = 'RETRY'::queue_status"),
        scalarLong("SELECT count(*) FROM job_queue WHERE status = 'FAILED'::queue_status"),
        scalarLong("SELECT count(*) FROM worker_registry WHERE last_heartbeat_at >= NOW() - INTERVAL '30 seconds'")
    );
  }

  public List<QueueJob> listJobs(Optional<String> queueName, Optional<QueueStatus> status, Optional<String> search, int limit) {
    var sql = new StringBuilder("SELECT * FROM job_queue WHERE 1 = 1");
    var parameters = new ArrayList<Object>();

    queueName.ifPresent(value -> {
      sql.append(" AND queue_name = ?");
      parameters.add(value);
    });
    status.ifPresent(value -> {
      sql.append(" AND status = ?::queue_status");
      parameters.add(value.name());
    });
    search.filter(value -> !value.isBlank()).ifPresent(value -> {
      sql.append(" AND (dedup_key ILIKE ? OR payload::text ILIKE ? OR COALESCE(locked_by, '') ILIKE ?)");
      var token = "%" + value + "%";
      parameters.add(token);
      parameters.add(token);
      parameters.add(token);
    });

    sql.append(" ORDER BY available_at ASC, id DESC LIMIT ?");
    parameters.add(limit);

    try (var connection = dataSource.getConnection();
         var statement = connection.prepareStatement(sql.toString())) {
      bindParameters(statement, parameters);
      try (var resultSet = statement.executeQuery()) {
        var jobs = new ArrayList<QueueJob>();
        while (resultSet.next()) {
          jobs.add(mapJob(resultSet));
        }
        return jobs;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Nao foi possivel listar os jobs", exception);
    }
  }

  public Optional<QueueJob> findJobById(long jobId) {
      var sql = "SELECT * FROM job_queue WHERE id = ?";
      try (var connection = dataSource.getConnection();
        var statement = connection.prepareStatement(sql)) {
      statement.setLong(1, jobId);
      try (var resultSet = statement.executeQuery()) {
        if (resultSet.next()) {
          return Optional.of(mapJob(resultSet));
        }
        return Optional.empty();
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Nao foi possivel consultar o job", exception);
    }
  }

  public List<WorkerSnapshot> listWorkers() {
    var sql = "SELECT * FROM worker_registry ORDER BY last_heartbeat_at DESC";
    try (var connection = dataSource.getConnection();
         var statement = connection.createStatement();
         var resultSet = statement.executeQuery(sql)) {
      var workers = new ArrayList<WorkerSnapshot>();
      while (resultSet.next()) {
        workers.add(new WorkerSnapshot(
            resultSet.getString("worker_id"),
            resultSet.getTimestamp("started_at").toInstant(),
            resultSet.getTimestamp("last_heartbeat_at").toInstant(),
            resultSet.getString("status"),
            resultSet.getLong("processed_count"),
            resultSet.getLong("failed_count")
        ));
      }
      return workers;
    } catch (SQLException exception) {
      throw new IllegalStateException("Nao foi possivel listar os workers", exception);
    }
  }

  public void registerWorker(String workerId) {
    var sql = """
        INSERT INTO worker_registry (worker_id, last_heartbeat_at, status)
        VALUES (?, NOW(), 'ACTIVE')
        ON CONFLICT (worker_id) DO UPDATE
        SET last_heartbeat_at = EXCLUDED.last_heartbeat_at,
            status = 'ACTIVE'
        """;
    executeMutation(sql, statement -> statement.setString(1, workerId), "Nao foi possivel registrar worker");
  }

  public void heartbeatWorker(String workerId) {
    var sql = "UPDATE worker_registry SET last_heartbeat_at = NOW(), status = 'ACTIVE' WHERE worker_id = ?";
    executeMutation(sql, statement -> statement.setString(1, workerId), "Nao foi possivel atualizar heartbeat");
  }

  public void updateWorkerStats(String workerId, boolean success) {
    var sql = success
        ? "UPDATE worker_registry SET processed_count = processed_count + 1, last_heartbeat_at = NOW() WHERE worker_id = ?"
        : "UPDATE worker_registry SET failed_count = failed_count + 1, last_heartbeat_at = NOW() WHERE worker_id = ?";
    executeMutation(sql, statement -> statement.setString(1, workerId), "Nao foi possivel atualizar estatisticas do worker");
  }

  public void markWorkerStopped(String workerId) {
    var sql = "UPDATE worker_registry SET status = 'STOPPED', last_heartbeat_at = NOW() WHERE worker_id = ?";
    executeMutation(sql, statement -> statement.setString(1, workerId), "Nao foi possivel marcar worker como parado");
  }

  public void recordExecution(long jobId, String workerId, int attemptNumber, String outcome, String errorMessage, Instant startedAt, Instant finishedAt) {
    var sql = """
        INSERT INTO job_execution_history (job_id, worker_id, attempt_number, outcome, error_message, started_at, finished_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """;
    executeMutation(sql, statement -> {
      statement.setLong(1, jobId);
      statement.setString(2, workerId);
      statement.setInt(3, attemptNumber);
      statement.setString(4, outcome);
      if (errorMessage == null || errorMessage.isBlank()) {
        statement.setNull(5, Types.VARCHAR);
      } else {
        statement.setString(5, errorMessage);
      }
      statement.setTimestamp(6, Timestamp.from(startedAt));
      statement.setTimestamp(7, Timestamp.from(finishedAt));
    }, "Nao foi possivel registrar historico de execucao");
  }

  public boolean requeueJob(long jobId, String correlationId) {
    var sql = """
        UPDATE job_queue
        SET status = 'RETRY'::queue_status,
            attempts = 0,
            available_at = NOW(),
            locked_at = NULL,
            locked_by = NULL,
            last_error = NULL,
            job_version = job_version + 1,
            updated_at = NOW()
        WHERE id = ?
          AND status = 'FAILED'::queue_status
        RETURNING *
        """;

    try (var connection = dataSource.getConnection();
         var statement = connection.prepareStatement(sql)) {
      connection.setAutoCommit(false);
      statement.setLong(1, jobId);
      try (var resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          connection.commit();
          connection.setAutoCommit(true);
          return false;
        }
        var updated = mapJob(resultSet);
        var payload = JsonNodeFactory.instance.objectNode();
        payload.put("action", "manual-requeue");
        insertOutboxEvent(connection, QueueEventEnvelope.of(
            QueueEventType.JOB_REQUEUED,
            UUID.randomUUID().toString(),
            Instant.now(),
            "queue-platform",
            correlationId,
            updated.queueName(),
            updated.id(),
            updated.jobVersion(),
            payload
        ));
        insertMessageOutbox(connection, updated.id(), updated.queueName(), correlationId, Instant.now());
        notifyQueuePublish(connection, updated.queueName());
        connection.commit();
        connection.setAutoCommit(true);
        return true;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Nao foi possivel reenfileirar o job", exception);
    }
  }

  public Optional<QueueJob> markJobProcessingFromBroker(long jobId, String workerId, String brokerMessageId, String correlationId) {
    var sql = """
        UPDATE job_queue
        SET status = 'PROCESSING'::queue_status,
            locked_at = NOW(),
            locked_by = ?,
            broker_message_id = ?,
            consumed_at = NOW(),
            job_version = job_version + 1,
            updated_at = NOW()
        WHERE id = ?
          AND status IN ('PENDING'::queue_status, 'RETRY'::queue_status)
        RETURNING *
        """;

    try (var connection = dataSource.getConnection();
         var statement = connection.prepareStatement(sql)) {
      connection.setAutoCommit(false);
      statement.setString(1, workerId);
      statement.setString(2, brokerMessageId == null ? "" : brokerMessageId);
      statement.setLong(3, jobId);
      try (var resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          connection.commit();
          connection.setAutoCommit(true);
          return Optional.empty();
        }
        var job = mapJob(resultSet);
        var payload = JsonNodeFactory.instance.objectNode();
        payload.put("workerId", workerId);
        payload.put("attempt", job.attempts() + 1);
        insertOutboxEvent(connection, QueueEventEnvelope.of(
            QueueEventType.JOB_CLAIMED,
            UUID.randomUUID().toString(),
            Instant.now(),
            "queue-platform",
            correlationId,
            job.queueName(),
            job.id(),
            job.jobVersion(),
            payload
        ));
        connection.commit();
        connection.setAutoCommit(true);
        return Optional.of(job);
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Nao foi possivel marcar job como PROCESSING via broker", exception);
    }
  }

  public void enqueueBrokerMessage(long jobId, String queueName, Instant nextAttemptAt, String correlationId) {
    var sql = """
        INSERT INTO message_outbox (
          job_id,
          exchange_name,
          routing_key,
          message_id,
          payload,
          next_attempt_at
        )
        VALUES (?, ?, ?, ?, ?, COALESCE(?, NOW()))
        """;

    try (var connection = dataSource.getConnection();
         var statement = connection.prepareStatement(sql)) {
      connection.setAutoCommit(false);
      statement.setLong(1, jobId);
      statement.setString(2, DEFAULT_EXCHANGE);
      statement.setString(3, queueName);
      var messageId = UUID.randomUUID().toString();
      statement.setString(4, messageId);
      var payload = JsonNodeFactory.instance.objectNode();
      payload.put("jobId", jobId);
      payload.put("queueName", queueName);
      payload.put("messageId", messageId);
      payload.put("correlationId", correlationId == null ? "" : correlationId);
      statement.setObject(5, jsonb(payload));
      if (nextAttemptAt == null) {
        statement.setNull(6, Types.TIMESTAMP_WITH_TIMEZONE);
      } else {
        statement.setTimestamp(6, Timestamp.from(nextAttemptAt));
      }
      statement.executeUpdate();
      notifyQueuePublish(connection, queueName);
      connection.commit();
      connection.setAutoCommit(true);
    } catch (SQLException exception) {
      throw new IllegalStateException("Nao foi possivel enfileirar mensagem para o broker", exception);
    }
  }

  public List<MessageOutboxRecord> claimPendingMessageOutbox(int limit) {
    var sql = """
        WITH candidates AS (
          SELECT outbox_id
          FROM message_outbox
          WHERE status = 'PENDING'
            AND next_attempt_at <= NOW()
          ORDER BY outbox_id
          FOR UPDATE SKIP LOCKED
          LIMIT ?
        )
        UPDATE message_outbox outbox
        SET status = 'SENDING',
            attempts = outbox.attempts + 1,
            updated_at = NOW()
        FROM candidates
        WHERE outbox.outbox_id = candidates.outbox_id
        RETURNING outbox.outbox_id,
                  outbox.job_id,
                  outbox.exchange_name,
                  outbox.routing_key,
                  outbox.message_id,
                  outbox.payload::text,
                  outbox.attempts
        """;

    try (var connection = dataSource.getConnection();
         var statement = connection.prepareStatement(sql)) {
      connection.setAutoCommit(false);
      statement.setInt(1, limit);
      var messages = new ArrayList<MessageOutboxRecord>();
      try (var resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          messages.add(new MessageOutboxRecord(
              resultSet.getLong("outbox_id"),
              resultSet.getLong("job_id"),
              resultSet.getString("exchange_name"),
              resultSet.getString("routing_key"),
              resultSet.getString("message_id"),
              resultSet.getString("payload"),
              resultSet.getInt("attempts")
          ));
        }
      }
      connection.commit();
      connection.setAutoCommit(true);
      return messages;
    } catch (SQLException exception) {
      throw new IllegalStateException("Nao foi possivel reservar mensagens da message_outbox", exception);
    }
  }

  public void markMessageOutboxSent(long outboxId) {
    var sql = """
        UPDATE message_outbox
        SET status = 'SENT',
            published_at = NOW(),
            updated_at = NOW(),
            last_error = NULL
        WHERE outbox_id = ?
        """;
    executeMutation(sql, statement -> statement.setLong(1, outboxId), "Nao foi possivel marcar message_outbox como enviada");
  }

  public void markMessageOutboxFailed(long outboxId, String errorMessage) {
    var sql = """
        UPDATE message_outbox
        SET status = 'PENDING',
            next_attempt_at = NOW() + make_interval(secs => LEAST((2 ^ LEAST(attempts, 8))::int, 60)),
            updated_at = NOW(),
            last_error = ?
        WHERE outbox_id = ?
        """;
    executeMutation(sql, statement -> {
      statement.setString(1, errorMessage == null ? "Erro de publicacao no broker" : errorMessage);
      statement.setLong(2, outboxId);
    }, "Nao foi possivel registrar falha da message_outbox");
  }

  public List<OutboxEvent> claimPendingOutboxEvents(int limit) {
    var sql = """
        WITH candidates AS (
          SELECT outbox_id
          FROM event_outbox
          WHERE status = 'PENDING'
            AND next_attempt_at <= NOW()
          ORDER BY outbox_id
          FOR UPDATE SKIP LOCKED
          LIMIT ?
        )
        UPDATE event_outbox outbox
        SET status = 'SENDING',
            attempts = outbox.attempts + 1,
            updated_at = NOW()
        FROM candidates
        WHERE outbox.outbox_id = candidates.outbox_id
        RETURNING outbox.outbox_id, outbox.event_id, outbox.aggregate_id, outbox.aggregate_version, outbox.occurred_at, outbox.payload::text
        """;

    try (var connection = dataSource.getConnection();
         var statement = connection.prepareStatement(sql)) {
      connection.setAutoCommit(false);
      statement.setInt(1, limit);
      var events = new ArrayList<OutboxEvent>();
      try (var resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          events.add(new OutboxEvent(
              resultSet.getLong("outbox_id"),
              resultSet.getString("event_id"),
              resultSet.getLong("aggregate_id"),
              resultSet.getLong("aggregate_version"),
              resultSet.getTimestamp("occurred_at").toInstant(),
              resultSet.getString("payload")
          ));
        }
      }
      connection.commit();
      connection.setAutoCommit(true);
      return events;
    } catch (SQLException exception) {
      throw new IllegalStateException("Nao foi possivel reservar eventos da outbox", exception);
    }
  }

  public void markOutboxSent(long outboxId) {
    var sql = """
        UPDATE event_outbox
        SET status = 'SENT',
            sent_at = NOW(),
            updated_at = NOW(),
            last_error = NULL
        WHERE outbox_id = ?
        """;
    executeMutation(sql, statement -> statement.setLong(1, outboxId), "Nao foi possivel marcar outbox como enviada");
  }

  public void markOutboxFailed(long outboxId, String errorMessage) {
    var sql = """
        UPDATE event_outbox
        SET status = 'PENDING',
            next_attempt_at = NOW() + make_interval(secs => LEAST((2 ^ LEAST(attempts, 8))::int, 60)),
            updated_at = NOW(),
            last_error = ?
        WHERE outbox_id = ?
        """;
    executeMutation(sql, statement -> {
      statement.setString(1, errorMessage == null ? "Erro de publicacao" : errorMessage);
      statement.setLong(2, outboxId);
    }, "Nao foi possivel registrar falha de envio da outbox");
  }

  public List<OutboxEvent> listEventsSince(long cursor, int limit) {
    var sql = """
        SELECT outbox_id, event_id, aggregate_id, aggregate_version, occurred_at, payload::text
        FROM event_outbox
        WHERE outbox_id > ?
          AND status = 'SENT'
        ORDER BY outbox_id
        LIMIT ?
        """;

    try (var connection = dataSource.getConnection();
         var statement = connection.prepareStatement(sql)) {
      statement.setLong(1, cursor);
      statement.setInt(2, limit);
      var events = new ArrayList<OutboxEvent>();
      try (var resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          events.add(new OutboxEvent(
              resultSet.getLong("outbox_id"),
              resultSet.getString("event_id"),
              resultSet.getLong("aggregate_id"),
              resultSet.getLong("aggregate_version"),
              resultSet.getTimestamp("occurred_at").toInstant(),
              resultSet.getString("payload")
          ));
        }
      }
      return events;
    } catch (SQLException exception) {
      throw new IllegalStateException("Nao foi possivel listar eventos por cursor", exception);
    }
  }

  private QueueJob mapJob(ResultSet resultSet) throws SQLException {
    try {
      return new QueueJob(
          resultSet.getLong("id"),
          resultSet.getLong("job_version"),
          resultSet.getString("queue_name"),
          resultSet.getString("dedup_key"),
          JsonSupport.MAPPER.readTree(resultSet.getString("payload")),
          QueueStatus.valueOf(resultSet.getString("status")),
          resultSet.getTimestamp("available_at").toInstant(),
          resultSet.getInt("attempts"),
          resultSet.getInt("max_attempts"),
          timestampToInstant(resultSet.getTimestamp("locked_at")),
          resultSet.getString("locked_by"),
          resultSet.getString("last_error"),
          resultSet.getTimestamp("created_at").toInstant(),
          resultSet.getTimestamp("updated_at").toInstant()
      );
    } catch (Exception exception) {
      throw new SQLException("Nao foi possivel desserializar o payload do job", exception);
    }
  }

  private Instant timestampToInstant(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
  }

  private PGobject jsonb(JsonNode payload) throws SQLException {
    var object = new PGobject();
    object.setType("jsonb");
    object.setValue(payload.toString());
    return object;
  }

  private void insertOutboxEvent(java.sql.Connection connection, QueueEventEnvelope envelope) throws SQLException {
    var sql = """
        INSERT INTO event_outbox (event_id, aggregate_type, aggregate_id, aggregate_version, occurred_at, payload)
        VALUES (?, 'job', ?, ?, ?, ?)
        """;

    try (var statement = connection.prepareStatement(sql)) {
      statement.setString(1, envelope.eventId());
      statement.setLong(2, envelope.jobId());
      statement.setLong(3, envelope.jobVersion());
      statement.setTimestamp(4, Timestamp.from(envelope.occurredAt()));
      statement.setObject(5, jsonb(JsonSupport.MAPPER.valueToTree(envelope)));
      statement.executeUpdate();
    }
  }

  private void insertMessageOutbox(java.sql.Connection connection, long jobId, String queueName, String correlationId, Instant nextAttemptAt) throws SQLException {
    var sql = """
        INSERT INTO message_outbox (
          job_id,
          exchange_name,
          routing_key,
          message_id,
          payload,
          next_attempt_at
        )
        VALUES (?, ?, ?, ?, ?, COALESCE(?, NOW()))
        """;

    var messageId = UUID.randomUUID().toString();
    var payload = JsonNodeFactory.instance.objectNode();
    payload.put("jobId", jobId);
    payload.put("queueName", queueName);
    payload.put("messageId", messageId);
    payload.put("correlationId", correlationId == null ? "" : correlationId);

    try (var statement = connection.prepareStatement(sql)) {
      statement.setLong(1, jobId);
      statement.setString(2, DEFAULT_EXCHANGE);
      statement.setString(3, queueName);
      statement.setString(4, messageId);
      statement.setObject(5, jsonb(payload));
      if (nextAttemptAt == null) {
        statement.setNull(6, Types.TIMESTAMP_WITH_TIMEZONE);
      } else {
        statement.setTimestamp(6, Timestamp.from(nextAttemptAt));
      }
      statement.executeUpdate();
    }
  }

  private void notifyQueuePublish(java.sql.Connection connection, String queueName) throws SQLException {
    try (var statement = connection.prepareStatement("SELECT pg_notify('queue_publish', ?)")) {
      statement.setString(1, queueName == null ? "default" : queueName);
      statement.execute();
    }
  }

  private static String env(String key, String fallback) {
    var value = System.getenv(key);
    return value == null || value.isBlank() ? fallback : value;
  }

  private long scalarLong(String sql) {
        try (var connection = dataSource.getConnection();
          var statement = connection.createStatement();
          var resultSet = statement.executeQuery(sql)) {
      resultSet.next();
      return resultSet.getLong(1);
    } catch (SQLException exception) {
      throw new IllegalStateException("Nao foi possivel executar consulta escalar", exception);
    }
  }

  private double scalarDouble(String sql) {
        try (var connection = dataSource.getConnection();
          var statement = connection.createStatement();
          var resultSet = statement.executeQuery(sql)) {
      resultSet.next();
      return resultSet.getDouble(1);
    } catch (SQLException exception) {
      throw new IllegalStateException("Nao foi possivel executar consulta escalar", exception);
    }
  }

  private void executeMutation(String sql, SqlConsumer consumer, String errorMessage) {
        try (var connection = dataSource.getConnection();
          var statement = connection.prepareStatement(sql)) {
      consumer.accept(statement);
      statement.executeUpdate();
    } catch (SQLException exception) {
      throw new IllegalStateException(errorMessage, exception);
    }
  }

  private void bindParameters(PreparedStatement statement, List<Object> parameters) throws SQLException {
    for (int index = 0; index < parameters.size(); index++) {
      var value = parameters.get(index);
      var parameterIndex = index + 1;
      if (value instanceof String stringValue) {
        statement.setString(parameterIndex, stringValue);
      } else if (value instanceof Integer integerValue) {
        statement.setInt(parameterIndex, integerValue);
      } else {
        statement.setObject(parameterIndex, value);
      }
    }
  }

  @FunctionalInterface
  private interface SqlConsumer {
    void accept(PreparedStatement statement) throws SQLException;
  }

  public record EnqueueResult(long jobId, boolean created) {
  }

  public record RetryResult(QueueStatus status, int attempts, Instant availableAt, QueueJob job) {
  }

  public record MessageOutboxRecord(
      long outboxId,
      long jobId,
      String exchangeName,
      String routingKey,
      String messageId,
      String payload,
      int attempts
  ) {
  }
}