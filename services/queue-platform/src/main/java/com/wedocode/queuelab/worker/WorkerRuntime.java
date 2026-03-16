package com.wedocode.queuelab.worker;

import com.wedocode.queuelab.core.AppConfig;
import com.wedocode.queuelab.core.QueueJob;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.postgresql.PGConnection;

public final class WorkerRuntime {
  private final DataSource dataSource;
  private final WorkerRuntimeRepository repository;
  private final AppConfig config;
  private final JobProcessor processor;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private final CountDownLatch shutdownLatch = new CountDownLatch(1);
  private final Object wakeSignal = new Object();
  private final List<Thread> workerThreads = new CopyOnWriteArrayList<>();
  private Thread listenerThread;
  private final String runtimeId = UUID.randomUUID().toString().substring(0, 8);

  public WorkerRuntime(DataSource dataSource, WorkerRuntimeRepository repository, AppConfig config, JobProcessor processor) {
    this.dataSource = dataSource;
    this.repository = repository;
    this.config = config;
    this.processor = processor;
  }

  public void start() {
    if (!running.compareAndSet(false, true)) {
      return;
    }

    listenerThread = Thread.ofVirtual().name("queue-listener").start(this::listenForNotifications);

    for (int index = 0; index < config.workerThreads(); index++) {
      var workerId = "worker-" + runtimeId + "-" + index;
      var thread = Thread.ofVirtual().name(workerId).start(() -> runWorkerLoop(workerId));
      workerThreads.add(thread);
    }
  }

  public void await() {
    try {
      shutdownLatch.await();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      stop();
    }
  }

  public void stop() {
    if (!running.compareAndSet(true, false)) {
      return;
    }

    workerThreads.forEach(Thread::interrupt);
    if (listenerThread != null) {
      listenerThread.interrupt();
    }
    synchronized (wakeSignal) {
      wakeSignal.notifyAll();
    }
    shutdownLatch.countDown();
  }

  private void runWorkerLoop(String workerId) {
    repository.registerWorker(workerId);
    long nextHeartbeatAt = System.nanoTime();
    try {
      while (running.get()) {
        long now = System.nanoTime();
        if (now >= nextHeartbeatAt) {
          repository.heartbeatWorker(workerId);
          nextHeartbeatAt = now + TimeUnit.SECONDS.toNanos(config.heartbeatIntervalSeconds());
        }

        var jobs = repository.claimReadyJobs(workerId, config.claimBatchSize(), correlationId("claim", workerId, 0));
        if (jobs.isEmpty()) {
          repository.reconcileStuckJobs(Duration.ofSeconds(config.processingTimeoutSeconds()), correlationId("reconcile", workerId, 0));
          waitForSignal();
          continue;
        }

        for (QueueJob job : jobs) {
          if (!running.get()) {
            break;
          }
          processSingleJob(workerId, job);
        }
      }
    } finally {
      repository.markWorkerStopped(workerId);
    }
  }

  private void processSingleJob(String workerId, QueueJob job) {
    var correlationId = correlationId("job", workerId, job.id());
    var startedAt = Instant.now();
    try {
      processor.process(job);
      repository.markDone(job.id(), workerId, correlationId);
      repository.recordExecution(job.id(), workerId, job.attempts() + 1, "DONE", null, startedAt, Instant.now());
      repository.updateWorkerStats(workerId, true);
    } catch (TransientProcessingException exception) {
      repository.scheduleRetry(job.id(), workerId, exception.getMessage(), correlationId);
      repository.recordExecution(job.id(), workerId, job.attempts() + 1, "RETRY", exception.getMessage(), startedAt, Instant.now());
      repository.updateWorkerStats(workerId, false);
    } catch (PermanentProcessingException exception) {
      repository.markFailed(job.id(), workerId, exception.getMessage(), correlationId);
      repository.recordExecution(job.id(), workerId, job.attempts() + 1, "FAILED", exception.getMessage(), startedAt, Instant.now());
      repository.updateWorkerStats(workerId, false);
    } catch (RuntimeException exception) {
      repository.scheduleRetry(job.id(), workerId, "Erro inesperado: " + exception.getMessage(), correlationId);
      repository.recordExecution(job.id(), workerId, job.attempts() + 1, "RETRY", exception.getMessage(), startedAt, Instant.now());
      repository.updateWorkerStats(workerId, false);
    }
  }

  private String correlationId(String prefix, String workerId, long jobId) {
    return prefix + ":" + workerId + ":" + jobId + ":" + UUID.randomUUID();
  }

  private void waitForSignal() {
    try {
      synchronized (wakeSignal) {
        wakeSignal.wait(config.fallbackPollMillis());
      }
      Thread.sleep(config.idleSleepMillis());
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    }
  }

  private void listenForNotifications() {
    while (running.get()) {
      try (var connection = dataSource.getConnection();
           var statement = connection.createStatement()) {
        statement.execute("LISTEN job_queue_new");
        var pgConnection = connection.unwrap(PGConnection.class);
        while (running.get()) {
          var notifications = pgConnection.getNotifications(config.fallbackPollMillis());
          if (notifications != null && notifications.length > 0) {
            wakeAllWorkers();
          }
        }
      } catch (Exception exception) {
        if (!running.get()) {
          return;
        }
        try {
          Thread.sleep(1500);
        } catch (InterruptedException interruptedException) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }

  private void wakeAllWorkers() {
    synchronized (wakeSignal) {
      wakeSignal.notifyAll();
    }
  }
}