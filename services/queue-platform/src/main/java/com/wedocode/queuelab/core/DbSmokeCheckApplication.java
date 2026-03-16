package com.wedocode.queuelab.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DbSmokeCheckApplication {
  private static final Logger LOGGER = LoggerFactory.getLogger(DbSmokeCheckApplication.class);

  private DbSmokeCheckApplication() {
  }

  public static void main(String[] args) {
    var config = AppConfig.fromEnv();
    var dataSource = DataSourceFactory.create(config);
    var repository = new QueueRepository(dataSource);
    try {
      var snapshot = repository.fetchDashboard();
      LOGGER.info("Dashboard consultado com sucesso: {}", snapshot);
    } catch (Exception exception) {
      LOGGER.error("Falha no smoke check do banco", exception);
      throw new IllegalStateException("Smoke check do banco falhou", exception);
    }
  }
}