package com.wedocode.queuelab.core;

import java.sql.DriverManager;

public final class DatabaseBootstrapApplication {
  private DatabaseBootstrapApplication() {
  }

  public static void main(String[] args) throws Exception {
    var adminUrl = env("QUEUE_ADMIN_DB_URL", "jdbc:postgresql://localhost:5432/postgres");
    var user = env("QUEUE_DB_USER", "postgres");
    var password = env("QUEUE_DB_PASSWORD", "postgres");
    var databaseName = env("QUEUE_TARGET_DB", "queue_lab");

    try (var connection = DriverManager.getConnection(adminUrl, user, password);
         var statement = connection.createStatement()) {
      statement.execute("CREATE DATABASE " + databaseName);
      System.out.println("Banco criado: " + databaseName);
    } catch (Exception exception) {
      var message = exception.getMessage() == null ? "" : exception.getMessage().toLowerCase();
      if (message.contains("already exists")) {
        System.out.println("Banco ja existe: " + databaseName);
        return;
      }
      throw exception;
    }
  }

  private static String env(String key, String fallback) {
    var value = System.getenv(key);
    return value == null || value.isBlank() ? fallback : value;
  }
}