package com.wedocode.queuelab.core;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;

public final class DataSourceFactory {
  private DataSourceFactory() {
  }

  public static DataSource create(AppConfig config) {
    var hikariConfig = new HikariConfig();
    hikariConfig.setJdbcUrl(config.jdbcUrl());
    hikariConfig.setUsername(config.dbUser());
    hikariConfig.setPassword(config.dbPassword());
    hikariConfig.setMaximumPoolSize(Math.max(config.workerThreads() + 4, 8));
    hikariConfig.setMinimumIdle(2);
    hikariConfig.setAutoCommit(true);
    hikariConfig.setPoolName("queue-lab-pool");
    return new HikariDataSource(hikariConfig);
  }
}