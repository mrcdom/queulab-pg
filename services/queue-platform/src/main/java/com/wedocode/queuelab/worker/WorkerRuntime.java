package com.wedocode.queuelab.worker;

import com.wedocode.queuelab.core.AppConfig;
import com.wedocode.queuelab.core.QueueJob;
import com.wedocode.queuelab.core.QueueRepository;
import java.sql.Connection;
import java.sql.Statement;
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
import org.postgresql.PGNotification;

public final class WorkerRuntime {
  private final DataSource dataSource;
  private final QueueRepository repository;
  private final AppConfig config;
  private final JobProcessor processor;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private final CountDownLatch shutdownLatch = new CountDownLatch(1);
  private final Object wakeSignal = new Object();
  private final List<Thread> workerThreads = new CopyOnWriteArrayList<>();
  private Thread listenerThread;
  private final String runtimeId = UUID.randomUUID().toString().substring(0, 8);

  public WorkerRuntime(DataSource dataSource, QueueRepository repository, AppConfig config, JobProcessor processor) {
    this.dataSource = dataSource;
    this.repository = repository;
    this.config = config;
    this.processor = processor;
  }

  public void start() {
    if (!running.compareAndSet(false, true)) {
      return;
    }

    listenerThread = new Thread(this::listenForNotifications, "queue-listener");
    listenerThread.start();

    for (int index = 0; index < config.workerThreads(); index++) {
      String workerId = "worker-" + runtimeId + "-" + index;
      Thread thread = new Thread(() -> runWorkerLoop(workerId), workerId);
      workerThreads.add(thread);
      thread.start();
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

        List<QueueJob> jobs = repository.claimReadyJobs(workerId, config.claimBatchSize());
        if (jobs.isEmpty()) {
          repository.reconcileStuckJobs(Duration.ofSeconds(config.processingTimeoutSeconds()));
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
    Instant startedAt = Instant.now();
    try {
      processor.process(job);
      repository.markDone(job.id(), workerId);
      repository.recordExecution(job.id(), workerId, job.attempts() + 1, "DONE", null, startedAt, Instant.now());
      repository.updateWorkerStats(workerId, true);
    } catch (TransientProcessingException exception) {
      repository.scheduleRetry(job.id(), workerId, exception.getMessage());
      repository.recordExecution(job.id(), workerId, job.attempts() + 1, "RETRY", exception.getMessage(), startedAt, Instant.now());
      repository.updateWorkerStats(workerId, false);
    } catch (PermanentProcessingException exception) {
      repository.markFailed(job.id(), workerId, exception.getMessage());
      repository.recordExecution(job.id(), workerId, job.attempts() + 1, "FAILED", exception.getMessage(), startedAt, Instant.now());
      repository.updateWorkerStats(workerId, false);
    } catch (RuntimeException exception) {
      repository.scheduleRetry(job.id(), workerId, "Erro inesperado: " + exception.getMessage());
      repository.recordExecution(job.id(), workerId, job.attempts() + 1, "RETRY", exception.getMessage(), startedAt, Instant.now());
      repository.updateWorkerStats(workerId, false);
    }
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
      try (Connection connection = dataSource.getConnection();
           Statement statement = connection.createStatement()) {
        statement.execute("LISTEN job_queue_new");
        PGConnection pgConnection = connection.unwrap(PGConnection.class);
        while (running.get()) {
          PGNotification[] notifications = pgConnection.getNotifications(config.fallbackPollMillis());
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