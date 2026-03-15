package com.wedocode.queuelab.worker;

@SuppressWarnings("serial")
public final class PermanentProcessingException extends Exception {
  public PermanentProcessingException(String message) {
    super(message);
  }
}