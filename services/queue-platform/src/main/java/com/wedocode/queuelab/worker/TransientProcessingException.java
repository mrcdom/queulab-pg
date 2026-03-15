package com.wedocode.queuelab.worker;

@SuppressWarnings("serial")
public final class TransientProcessingException extends Exception {
  public TransientProcessingException(String message) {
    super(message);
  }
}