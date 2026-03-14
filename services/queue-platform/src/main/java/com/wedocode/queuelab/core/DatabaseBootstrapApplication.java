package com.wedocode.queuelab.core;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

public final class DatabaseBootstrapApplication {
  private DatabaseBootstrapApplication() {
  }

  public static void main(String[] args) throws Exception {
    String adminUrl = env("QUEUE_ADMIN_DB_URL", "jdbc:postgresql://localhost:5432/postgres");
    String user = env("QUEUE_DB_USER", "postgres");
    String password = env("QUEUE_DB_PASSWORD", "postgres");
    String databaseName = env("QUEUE_TARGET_DB", "queue_lab");

    try (Connection connection = DriverManager.getConnection(adminUrl, user, password);
         Statement statement = connection.createStatement()) {
      statement.execute("CREATE DATABASE " + databaseName);
      System.out.println("Banco criado: " + databaseName);
    } catch (Exception exception) {
      String message = exception.getMessage() == null ? "" : exception.getMessage().toLowerCase();
      if (message.contains("already exists")) {
        System.out.println("Banco ja existe: " + databaseName);
        return;
      }
      throw exception;
    }
  }

  private static String env(String key, String fallback) {
    String value = System.getenv(key);
    return value == null || value.isBlank() ? fallback : value;
  }
}