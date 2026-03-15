package com.wedocode.queuelab.core;

import java.nio.file.Path;
import org.flywaydb.core.Flyway;

public final class MigrationApplication {
  private MigrationApplication() {
  }

  public static void main(String[] args) {
    var config = AppConfig.fromEnv();
    var pathFromEnv = System.getenv("QUEUE_MIGRATIONS_PATH");
    var migrationsPath = Path.of(pathFromEnv == null || pathFromEnv.isBlank()
        ? "../../database/migrations"
        : pathFromEnv)
        .toAbsolutePath()
        .normalize();

    var flyway = Flyway.configure()
        .dataSource(config.jdbcUrl(), config.dbUser(), config.dbPassword())
        .locations("filesystem:" + migrationsPath)
        .baselineOnMigrate(false)
        .load();

    flyway.migrate();
    System.out.println("Migracoes aplicadas com sucesso em: " + migrationsPath);
  }
}