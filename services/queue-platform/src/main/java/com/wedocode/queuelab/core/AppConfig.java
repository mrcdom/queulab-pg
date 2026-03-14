package com.wedocode.queuelab.core;

public record AppConfig(
    String jdbcUrl,
    String dbUser,
    String dbPassword,
    int apiPort,
    int workerThreads,
    int claimBatchSize,
    int fallbackPollMillis,
    int idleSleepMillis,
    int processingTimeoutSeconds,
    int heartbeatIntervalSeconds,
    int simulatedMinLatencyMillis,
    int simulatedMaxLatencyMillis,
    boolean startEmbeddedWorkers
) {

  public static AppConfig fromEnv() {
    return new AppConfig(
        env("QUEUE_DB_URL", "jdbc:postgresql://localhost:5432/queue_lab"),
        env("QUEUE_DB_USER", "postgres"),
        env("QUEUE_DB_PASSWORD", "admin"),
        envInt("QUEUE_API_PORT", 7070),
        envInt("QUEUE_WORKER_THREADS", 3),
        envInt("QUEUE_CLAIM_BATCH_SIZE", 10),
        envInt("QUEUE_FALLBACK_POLL_MS", 4000),
        envInt("QUEUE_IDLE_SLEEP_MS", 750),
        envInt("QUEUE_PROCESSING_TIMEOUT_SECONDS", 90),
        envInt("QUEUE_HEARTBEAT_SECONDS", 10),
        envInt("QUEUE_SIMULATED_MIN_LATENCY_MS", 150),
        envInt("QUEUE_SIMULATED_MAX_LATENCY_MS", 900),
        envBoolean("QUEUE_START_EMBEDDED_WORKERS", false)
    );
  }

  private static String env(String key, String fallback) {
    String value = System.getenv(key);
    return value == null || value.isBlank() ? fallback : value;
  }

  private static int envInt(String key, int fallback) {
    String value = System.getenv(key);
    if (value == null || value.isBlank()) {
      return fallback;
    }
    return Integer.parseInt(value);
  }

  private static boolean envBoolean(String key, boolean fallback) {
    String value = System.getenv(key);
    if (value == null || value.isBlank()) {
      return fallback;
    }
    return Boolean.parseBoolean(value);
  }
}