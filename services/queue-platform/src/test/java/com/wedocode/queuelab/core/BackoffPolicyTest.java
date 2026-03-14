package com.wedocode.queuelab.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BackoffPolicyTest {
  @Test
  void shouldStartWithOneSecondForInvalidAttempt() {
    assertEquals(1, BackoffPolicy.secondsForAttempt(0));
  }

  @Test
  void shouldGrowExponentiallyForEarlyAttempts() {
    assertEquals(2, BackoffPolicy.secondsForAttempt(1));
    assertEquals(4, BackoffPolicy.secondsForAttempt(2));
    assertEquals(8, BackoffPolicy.secondsForAttempt(3));
  }

  @Test
  void shouldCapAtFifteenMinutes() {
    assertEquals(900, BackoffPolicy.secondsForAttempt(12));
    assertEquals(900, BackoffPolicy.secondsForAttempt(20));
  }
}