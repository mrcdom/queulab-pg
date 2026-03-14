package com.wedocode.queuelab.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.postgresql.util.PGobject;

public final class QueueRepository {
  private final DataSource dataSource;

  public QueueRepository(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  public EnqueueResult enqueue(QueueService.EnqueueCommand command) {
    String insertSql = """
        INSERT INTO job_queue (queue_name, dedup_key, payload, available_at, max_attempts)
        VALUES (?, ?, ?, COALESCE(?, NOW()), ?)
        ON CONFLICT DO NOTHING
        RETURNING id
        """;

    try (Connection connection = dataSource.getConnection();
         PreparedStatement statement = connection.prepareStatement(insertSql)) {
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

      try (ResultSet resultSet = statement.executeQuery()) {
        if (resultSet.next()) {
          return new EnqueueResult(resultSet.getLong("id"), true);
        }
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Nao foi possivel enfileirar o job", exception);
    }

    if (command.dedupKey() == null || command.dedupKey().isBlank()) {
      throw new IllegalStateException("Falha ao enfileirar job sem dedup_key e sem retorno do insert");
    }

    String findExistingSql = """
        SELECT id
        FROM job_queue
        WHERE queue_name = ?
          AND dedup_key = ?
          AND status IN ('PENDING'::queue_status, 'PROCESSING'::queue_status, 'RETRY'::queue_status)
        ORDER BY id DESC
        LIMIT 1
        """;

    try (Connection connection = dataSource.getConnection();
         PreparedStatement statement = connection.prepareStatement(findExistingSql)) {
      statement.setString(1, command.queueName());
      statement.setString(2, command.dedupKey());

      try (ResultSet resultSet = statement.executeQuery()) {
        if (resultSet.next()) {
          return new EnqueueResult(resultSet.getLong("id"), false);
        }
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Nao foi possivel localizar o job deduplicado", exception);
    }

    throw new IllegalStateException("Falha ao enfileirar job e localizar deduplicacao");
  }

  public List<QueueJob> claimReadyJobs(String workerId, int limit) {
    String sql = """
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
            updated_at = NOW()
        FROM candidates
        WHERE q.id = candidates.id
        RETURNING q.*
        """;

    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setInt(1, limit);
        statement.setString(2, workerId);
        List<QueueJob> jobs = new ArrayList<>();
        try (ResultSet resultSet = statement.executeQuery()) {
          while (resultSet.next()) {
            jobs.add(mapJob(resultSet));
          }
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

  public void markDone(long jobId, String workerId) {
    String sql = """
        UPDATE job_queue
        SET status = 'DONE'::queue_status,
            locked_at = NULL,
            locked_by = NULL,
            updated_at = NOW()
        WHERE id = ?
          AND status = 'PROCESSING'::queue_status
          AND locked_by = ?
        """;
    executeMutation(sql, statement -> {
      statement.setLong(1, jobId);
      statement.setString(2, workerId);
    }, "Nao foi possivel marcar job como DONE");
  }

  public RetryResult scheduleRetry(long jobId, String workerId, String errorMessage) {
    String sql = """
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
            updated_at = NOW()
        WHERE id = ?
          AND status = 'PROCESSING'::queue_status
          AND locked_by = ?
        RETURNING status, attempts, available_at
        """;

    try (Connection connection = dataSource.getConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, errorMessage);
      statement.setLong(2, jobId);
      statement.setString(3, workerId);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (resultSet.next()) {
          return new RetryResult(
              QueueStatus.valueOf(resultSet.getString("status")),
              resultSet.getInt("attempts"),
              resultSet.getTimestamp("available_at").toInstant()
          );
        }
      }
      throw new IllegalStateException("Job nao encontrado para retry");
    } catch (SQLException exception) {
      throw new IllegalStateException("Nao foi possivel reagendar job", exception);
    }
  }

  public void markFailed(long jobId, String workerId, String errorMessage) {
    String sql = """
        UPDATE job_queue
        SET attempts = attempts + 1,
            status = 'FAILED'::queue_status,
            last_error = ?,
            locked_at = NULL,
            locked_by = NULL,
            updated_at = NOW()
        WHERE id = ?
          AND status = 'PROCESSING'::queue_status
          AND locked_by = ?
        """;
    executeMutation(sql, statement -> {
      statement.setString(1, errorMessage);
      statement.setLong(2, jobId);
      statement.setString(3, workerId);
    }, "Nao foi possivel marcar job como FAILED");
  }

  public int reconcileStuckJobs(Duration timeout) {
    String sql = """
        UPDATE job_queue
        SET status = 'RETRY'::queue_status,
            locked_at = NULL,
            locked_by = NULL,
            available_at = NOW(),
            updated_at = NOW(),
            last_error = COALESCE(last_error, 'Reconciliado apos timeout de processamento')
        WHERE status = 'PROCESSING'::queue_status
          AND locked_at < NOW() - (? * INTERVAL '1 second')
        """;

    try (Connection connection = dataSource.getConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, timeout.getSeconds());
      return statement.executeUpdate();
    } catch (SQLException exception) {
      throw new IllegalStateException("Nao foi possivel reconciliar jobs presos", exception);
    }
  }

  public DashboardSnapshot fetchDashboard() {
    Map<QueueStatus, Long> statusCounts = new EnumMap<>(QueueStatus.class);
    for (QueueStatus status : QueueStatus.values()) {
      statusCounts.put(status, 0L);
    }

    String countsSql = "SELECT status, count(*) AS total FROM job_queue GROUP BY status";
    try (Connection connection = dataSource.getConnection();
         Statement statement = connection.createStatement();
         ResultSet resultSet = statement.executeQuery(countsSql)) {
      while (resultSet.next()) {
        statusCounts.put(QueueStatus.valueOf(resultSet.getString("status")), resultSet.getLong("total"));
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Nao foi possivel consultar os contadores do dashboard", exception);
    }

    List<QueueBacklog> backlogs = new ArrayList<>();
    String backlogSql = """
        SELECT queue_name,
         count(*) FILTER (WHERE status IN ('PENDING'::queue_status, 'RETRY'::queue_status) AND available_at <= NOW()) AS ready_jobs,
         count(*) FILTER (WHERE status = 'PROCESSING'::queue_status) AS processing_jobs,
         count(*) FILTER (WHERE status = 'FAILED'::queue_status) AS failed_jobs
        FROM job_queue
        GROUP BY queue_name
        ORDER BY queue_name
        """;
    try (Connection connection = dataSource.getConnection();
         Statement statement = connection.createStatement();
         ResultSet resultSet = statement.executeQuery(backlogSql)) {
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
    StringBuilder sql = new StringBuilder("SELECT * FROM job_queue WHERE 1 = 1");
    List<Object> parameters = new ArrayList<>();

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
      String token = "%" + value + "%";
      parameters.add(token);
      parameters.add(token);
      parameters.add(token);
    });

    sql.append(" ORDER BY available_at ASC, id DESC LIMIT ?");
    parameters.add(limit);

    try (Connection connection = dataSource.getConnection();
         PreparedStatement statement = connection.prepareStatement(sql.toString())) {
      bindParameters(statement, parameters);
      try (ResultSet resultSet = statement.executeQuery()) {
        List<QueueJob> jobs = new ArrayList<>();
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
    String sql = "SELECT * FROM job_queue WHERE id = ?";
    try (Connection connection = dataSource.getConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, jobId);
      try (ResultSet resultSet = statement.executeQuery()) {
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
    String sql = "SELECT * FROM worker_registry ORDER BY last_heartbeat_at DESC";
    try (Connection connection = dataSource.getConnection();
         Statement statement = connection.createStatement();
         ResultSet resultSet = statement.executeQuery(sql)) {
      List<WorkerSnapshot> workers = new ArrayList<>();
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
    String sql = """
        INSERT INTO worker_registry (worker_id, last_heartbeat_at, status)
        VALUES (?, NOW(), 'ACTIVE')
        ON CONFLICT (worker_id) DO UPDATE
        SET last_heartbeat_at = EXCLUDED.last_heartbeat_at,
            status = 'ACTIVE'
        """;
    executeMutation(sql, statement -> statement.setString(1, workerId), "Nao foi possivel registrar worker");
  }

  public void heartbeatWorker(String workerId) {
    String sql = "UPDATE worker_registry SET last_heartbeat_at = NOW(), status = 'ACTIVE' WHERE worker_id = ?";
    executeMutation(sql, statement -> statement.setString(1, workerId), "Nao foi possivel atualizar heartbeat");
  }

  public void updateWorkerStats(String workerId, boolean success) {
    String sql = success
        ? "UPDATE worker_registry SET processed_count = processed_count + 1, last_heartbeat_at = NOW() WHERE worker_id = ?"
        : "UPDATE worker_registry SET failed_count = failed_count + 1, last_heartbeat_at = NOW() WHERE worker_id = ?";
    executeMutation(sql, statement -> statement.setString(1, workerId), "Nao foi possivel atualizar estatisticas do worker");
  }

  public void markWorkerStopped(String workerId) {
    String sql = "UPDATE worker_registry SET status = 'STOPPED', last_heartbeat_at = NOW() WHERE worker_id = ?";
    executeMutation(sql, statement -> statement.setString(1, workerId), "Nao foi possivel marcar worker como parado");
  }

  public void recordExecution(long jobId, String workerId, int attemptNumber, String outcome, String errorMessage, Instant startedAt, Instant finishedAt) {
    String sql = """
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

  public boolean requeueJob(long jobId) {
    String sql = """
        UPDATE job_queue
        SET status = 'RETRY'::queue_status,
            attempts = 0,
            available_at = NOW(),
            locked_at = NULL,
            locked_by = NULL,
            last_error = NULL,
            updated_at = NOW()
        WHERE id = ?
          AND status = 'FAILED'::queue_status
        """;

    try (Connection connection = dataSource.getConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, jobId);
      return statement.executeUpdate() > 0;
    } catch (SQLException exception) {
      throw new IllegalStateException("Nao foi possivel reenfileirar o job", exception);
    }
  }

  private QueueJob mapJob(ResultSet resultSet) throws SQLException {
    try {
      return new QueueJob(
          resultSet.getLong("id"),
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
    PGobject object = new PGobject();
    object.setType("jsonb");
    object.setValue(payload.toString());
    return object;
  }

  private long scalarLong(String sql) {
    try (Connection connection = dataSource.getConnection();
         Statement statement = connection.createStatement();
         ResultSet resultSet = statement.executeQuery(sql)) {
      resultSet.next();
      return resultSet.getLong(1);
    } catch (SQLException exception) {
      throw new IllegalStateException("Nao foi possivel executar consulta escalar", exception);
    }
  }

  private double scalarDouble(String sql) {
    try (Connection connection = dataSource.getConnection();
         Statement statement = connection.createStatement();
         ResultSet resultSet = statement.executeQuery(sql)) {
      resultSet.next();
      return resultSet.getDouble(1);
    } catch (SQLException exception) {
      throw new IllegalStateException("Nao foi possivel executar consulta escalar", exception);
    }
  }

  private void executeMutation(String sql, SqlConsumer consumer, String errorMessage) {
    try (Connection connection = dataSource.getConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      consumer.accept(statement);
      statement.executeUpdate();
    } catch (SQLException exception) {
      throw new IllegalStateException(errorMessage, exception);
    }
  }

  private void bindParameters(PreparedStatement statement, List<Object> parameters) throws SQLException {
    for (int index = 0; index < parameters.size(); index++) {
      Object value = parameters.get(index);
      int parameterIndex = index + 1;
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

  public record RetryResult(QueueStatus status, int attempts, Instant availableAt) {
  }
}