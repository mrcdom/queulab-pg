package com.wedocode.queuelab.core.events;

public enum QueueEventType {
  JOB_CREATED("job.created"),
  JOB_CLAIMED("job.claimed"),
  JOB_RETRY_SCHEDULED("job.retry_scheduled"),
  JOB_COMPLETED("job.completed"),
  JOB_FAILED("job.failed"),
  JOB_REQUEUED("job.requeued"),
  JOB_RECOVERED("job.recovered"),
  OPERATION_ACCEPTED("operation.accepted"),
  OPERATION_REJECTED("operation.rejected"),
  OPERATION_STARTED("operation.started"),
  OPERATION_FINISHED("operation.finished");

  private final String wireName;

  QueueEventType(String wireName) {
    this.wireName = wireName;
  }

  public String wireName() {
    return wireName;
  }
}
