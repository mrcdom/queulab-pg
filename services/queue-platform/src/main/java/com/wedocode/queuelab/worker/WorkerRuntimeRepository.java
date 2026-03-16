package com.wedocode.queuelab.worker;

import com.wedocode.queuelab.core.QueueJob;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

public interface WorkerRuntimeRepository {
  void registerWorker(String workerId);

  void heartbeatWorker(String workerId);

  List<QueueJob> claimReadyJobs(String workerId, int limit, String correlationId);

  int reconcileStuckJobs(Duration timeout, String correlationId);

  void markWorkerStopped(String workerId);

  void markDone(long jobId, String workerId, String correlationId);

  void scheduleRetry(long jobId, String workerId, String errorMessage, String correlationId);

  void markFailed(long jobId, String workerId, String errorMessage, String correlationId);

  void recordExecution(long jobId, String workerId, int attemptNumber, String outcome, String errorMessage, Instant startedAt, Instant finishedAt);

  void updateWorkerStats(String workerId, boolean success);
}