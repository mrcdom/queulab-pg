package com.wedocode.queuelab.worker;

import com.wedocode.queuelab.core.QueueJob;

public interface JobProcessor {
  void process(QueueJob job) throws TransientProcessingException, PermanentProcessingException;
}