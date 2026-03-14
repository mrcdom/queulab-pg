package com.wedocode.queuelab.core;

public final class BackoffPolicy {
  private static final int MAX_DELAY_SECONDS = 900;

  private BackoffPolicy() {
  }

  public static int secondsForAttempt(int nextAttempt) {
    if (nextAttempt <= 0) {
      return 1;
    }

    long computedDelay = 1L << Math.min(nextAttempt, 30);
    return (int) Math.min(computedDelay, MAX_DELAY_SECONDS);
  }
}