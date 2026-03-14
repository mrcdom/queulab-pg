package com.wedocode.queuelab.core;

import java.util.List;
import java.util.Map;

public record DashboardSnapshot(
    Map<QueueStatus, Long> statusCounts,
    List<QueueBacklog> queueBacklogs,
    double averageWaitSeconds,
    double averageProcessingSeconds,
    long retryJobs,
    long dlqJobs,
    long activeWorkers
) {
}