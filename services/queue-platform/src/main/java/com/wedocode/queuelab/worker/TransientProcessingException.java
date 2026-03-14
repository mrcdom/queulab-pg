package com.wedocode.queuelab.worker;

public final class TransientProcessingException extends Exception {
  public TransientProcessingException(String message) {
    super(message);
  }
}