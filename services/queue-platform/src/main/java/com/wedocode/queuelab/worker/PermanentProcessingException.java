package com.wedocode.queuelab.worker;

public final class PermanentProcessingException extends Exception {
  public PermanentProcessingException(String message) {
    super(message);
  }
}