package com.wedocode.queuelab.core;

import java.sql.DriverManager;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DatabaseBootstrapApplication {
  private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseBootstrapApplication.class);
  private static final Pattern SAFE_DB_NAME = Pattern.compile("[A-Za-z0-9_]+");

  private DatabaseBootstrapApplication() {
  }

  public static void main(String[] args) throws Exception {
    var adminUrl = env("QUEUE_ADMIN_DB_URL", "jdbc:postgresql://localhost:5432/postgres");
    var user = env("QUEUE_DB_USER", "postgres");
    var password = env("QUEUE_DB_PASSWORD", "postgres");
    var databaseName = env("QUEUE_TARGET_DB", "queue_lab");
    ensureSafeDatabaseName(databaseName);

    try (var connection = DriverManager.getConnection(adminUrl, user, password);
         var statement = connection.createStatement()) {
      statement.execute("CREATE DATABASE " + databaseName);
      LOGGER.info("Banco criado: {}", databaseName);
    } catch (Exception exception) {
      var message = exception.getMessage() == null ? "" : exception.getMessage().toLowerCase();
      if (message.contains("already exists")) {
        LOGGER.info("Banco ja existe: {}", databaseName);
        return;
      }
      throw exception;
    }
  }

  private static void ensureSafeDatabaseName(String databaseName) {
    if (!SAFE_DB_NAME.matcher(databaseName).matches()) {
      throw new IllegalArgumentException("QUEUE_TARGET_DB invalido. Use apenas letras, numeros e underscore.");
    }
  }

  private static String env(String key, String fallback) {
    var value = System.getenv(key);
    return value == null || value.isBlank() ? fallback : value;
  }
}