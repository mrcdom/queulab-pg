package com.wedocode.queuelab.core;

public record QueueBacklog(
    String queueName,
    long readyJobs,
    long processingJobs,
    long failedJobs
) {
}