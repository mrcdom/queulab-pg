package com.wedocode.queuelab.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wedocode.queuelab.core.AppConfig;
import com.wedocode.queuelab.core.JsonSupport;
import com.wedocode.queuelab.core.QueueJob;
import com.wedocode.queuelab.core.QueueStatus;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class WorkerRuntimeTest {

  @Test
  void workersRunOnVirtualThreadsAndProcessAllJobs() throws Exception {
    int workerThreads = 4;
    int jobCount = 24;
    var repository = new FakeWorkerRepository(workerThreads, jobs(jobCount));
    var config = config(workerThreads);
    var runtime = new WorkerRuntime(failingDataSource(), repository, config, job -> {
      repository.processedJobIds.add(job.id());
    });

    runtime.start();
    waitUntil(() -> repository.processedJobIds.size() == jobCount, Duration.ofSeconds(3));
    runtime.stop();

    assertTrue(repository.registered.await(2, TimeUnit.SECONDS));
    waitUntil(() -> repository.stoppedWorkers.get() == workerThreads, Duration.ofSeconds(2));

    assertTrue(repository.workerThreadsVirtual.stream().allMatch(Boolean::booleanValue));
    assertEquals(jobCount, repository.processedJobIds.size());
    assertEquals(jobCount, repository.markDoneCount.get());
    assertEquals(0, repository.scheduleRetryCount.get());
    assertEquals(0, repository.markFailedCount.get());
  }

  @Test
  void transientFailureTriggersRetryPath() throws Exception {
    var repository = new FakeWorkerRepository(1, jobs(1));
    var config = config(1);
    var runtime = new WorkerRuntime(
        failingDataSource(),
        repository,
        config,
        job -> {
          throw new TransientProcessingException("transient");
        }
    );

    runtime.start();
    waitUntil(() -> repository.scheduleRetryCount.get() == 1, Duration.ofSeconds(2));
    runtime.stop();

    assertEquals(0, repository.markDoneCount.get());
    assertEquals(0, repository.markFailedCount.get());
    assertEquals(1, repository.scheduleRetryCount.get());
  }

  private static AppConfig config(int workerThreads) {
    return new AppConfig(
        "jdbc:postgresql://localhost:5432/queue_lab",
        "postgres",
        "admin",
        7070,
        workerThreads,
        1,
        25,
        5,
        30,
        1,
        10,
        10,
        false
    );
  }

  private static List<QueueJob> jobs(int count) {
    var jobs = new ArrayList<QueueJob>(count);
    for (int i = 0; i < count; i++) {
      var now = Instant.now();
      var payload = JsonSupport.MAPPER.createObjectNode();
      payload.put("channel", "EMAIL");
      payload.put("recipient", "user-" + i + "@example.com");
      jobs.add(new QueueJob(
          i + 1L,
          1L,
          "notification.send",
          null,
          payload,
          QueueStatus.PENDING,
          now,
          0,
          6,
          null,
          null,
          null,
          now,
          now
      ));
    }
    return jobs;
  }

  private static DataSource failingDataSource() {
    return (DataSource) Proxy.newProxyInstance(
        DataSource.class.getClassLoader(),
        new Class<?>[] {DataSource.class},
        (proxy, method, args) -> {
          String name = method.getName();
          if ("getConnection".equals(name)) {
            throw new SQLException("no database in test");
          }
          if ("isWrapperFor".equals(name)) {
            return false;
          }
          if ("unwrap".equals(name)) {
            throw new SQLException("unsupported");
          }
          if ("getLoginTimeout".equals(name)) {
            return 0;
          }
          if ("getParentLogger".equals(name)) {
            return java.util.logging.Logger.getGlobal();
          }
          return null;
        }
    );
  }

  private static void waitUntil(Check check, Duration timeout) throws Exception {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (check.ok()) {
        return;
      }
      Thread.sleep(10);
    }
    throw new AssertionError("Condition was not satisfied in " + timeout);
  }

  @FunctionalInterface
  private interface Check {
    boolean ok();
  }

  private static final class FakeWorkerRepository implements WorkerRuntimeRepository {
    private final CountDownLatch registered;
    private final Queue<QueueJob> jobs;
    private final Set<Long> processedJobIds = ConcurrentHashMap.newKeySet();
    private final List<Boolean> workerThreadsVirtual = new CopyOnWriteArrayList<>();
    private final AtomicInteger markDoneCount = new AtomicInteger();
    private final AtomicInteger scheduleRetryCount = new AtomicInteger();
    private final AtomicInteger markFailedCount = new AtomicInteger();
    private final AtomicInteger stoppedWorkers = new AtomicInteger();

    private FakeWorkerRepository(int workers, List<QueueJob> jobs) {
      this.registered = new CountDownLatch(workers);
      this.jobs = new ArrayDeque<>(jobs);
    }

    @Override
    public void registerWorker(String workerId) {
      workerThreadsVirtual.add(Thread.currentThread().isVirtual());
      registered.countDown();
    }

    @Override
    public void heartbeatWorker(String workerId) {
    }

    @Override
    public List<QueueJob> claimReadyJobs(String workerId, int limit, String correlationId) {
      synchronized (jobs) {
        if (jobs.isEmpty()) {
          return List.of();
        }
        var next = jobs.poll();
        return next == null ? List.of() : List.of(next);
      }
    }

    @Override
    public int reconcileStuckJobs(Duration timeout, String correlationId) {
      return 0;
    }

    @Override
    public void markWorkerStopped(String workerId) {
      stoppedWorkers.incrementAndGet();
    }

    @Override
    public void markDone(long jobId, String workerId, String correlationId) {
      markDoneCount.incrementAndGet();
    }

    @Override
    public void scheduleRetry(long jobId, String workerId, String errorMessage, String correlationId) {
      scheduleRetryCount.incrementAndGet();
    }

    @Override
    public void markFailed(long jobId, String workerId, String errorMessage, String correlationId) {
      markFailedCount.incrementAndGet();
    }

    @Override
    public void recordExecution(long jobId, String workerId, int attemptNumber, String outcome, String errorMessage, Instant startedAt, Instant finishedAt) {
    }

    @Override
    public void updateWorkerStats(String workerId, boolean success) {
    }
  }
}
