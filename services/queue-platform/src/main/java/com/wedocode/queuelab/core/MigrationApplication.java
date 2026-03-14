package com.wedocode.queuelab.core;

import java.nio.file.Path;
import org.flywaydb.core.Flyway;

public final class MigrationApplication {
  private MigrationApplication() {
  }

  public static void main(String[] args) {
    AppConfig config = AppConfig.fromEnv();
    String pathFromEnv = System.getenv("QUEUE_MIGRATIONS_PATH");
    Path migrationsPath = Path.of(pathFromEnv == null || pathFromEnv.isBlank()
        ? "../../database/migrations"
        : pathFromEnv)
        .toAbsolutePath()
        .normalize();

    Flyway flyway = Flyway.configure()
        .dataSource(config.jdbcUrl(), config.dbUser(), config.dbPassword())
        .locations("filesystem:" + migrationsPath)
      .baselineOnMigrate(false)
        .load();

    flyway.migrate();
    System.out.println("Migracoes aplicadas com sucesso em: " + migrationsPath);
  }
}