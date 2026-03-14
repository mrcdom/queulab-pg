package com.wedocode.queuelab.core;

public enum QueueStatus {
  PENDING,
  PROCESSING,
  RETRY,
  DONE,
  FAILED;

  public boolean isReady() {
    return this == PENDING || this == RETRY;
  }
}