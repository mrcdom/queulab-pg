package com.wedocode.queuelab.worker;

import com.wedocode.queuelab.core.QueueRepository;
import java.time.Duration;
import java.time.Instant;

public final class QueueRepositoryWorkerAdapter implements WorkerRuntimeRepository {
  private final QueueRepository repository;

  public QueueRepositoryWorkerAdapter(QueueRepository repository) {
    this.repository = repository;
  }

  @Override
  public void registerWorker(String workerId) {
    repository.registerWorker(workerId);
  }

  @Override
  public void heartbeatWorker(String workerId) {
    repository.heartbeatWorker(workerId);
  }

  @Override
  public java.util.List<com.wedocode.queuelab.core.QueueJob> claimReadyJobs(String workerId, int limit, String correlationId) {
    return repository.claimReadyJobs(workerId, limit, correlationId);
  }

  @Override
  public int reconcileStuckJobs(Duration timeout, String correlationId) {
    return repository.reconcileStuckJobs(timeout, correlationId);
  }

  @Override
  public void markWorkerStopped(String workerId) {
    repository.markWorkerStopped(workerId);
  }

  @Override
  public void markDone(long jobId, String workerId, String correlationId) {
    repository.markDone(jobId, workerId, correlationId);
  }

  @Override
  public void scheduleRetry(long jobId, String workerId, String errorMessage, String correlationId) {
    repository.scheduleRetry(jobId, workerId, errorMessage, correlationId);
  }

  @Override
  public void markFailed(long jobId, String workerId, String errorMessage, String correlationId) {
    repository.markFailed(jobId, workerId, errorMessage, correlationId);
  }

  @Override
  public void recordExecution(long jobId, String workerId, int attemptNumber, String outcome, String errorMessage, Instant startedAt, Instant finishedAt) {
    repository.recordExecution(jobId, workerId, attemptNumber, outcome, errorMessage, startedAt, finishedAt);
  }

  @Override
  public void updateWorkerStats(String workerId, boolean success) {
    repository.updateWorkerStats(workerId, success);
  }
}