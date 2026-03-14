package com.wedocode.queuelab.core;

import javax.sql.DataSource;

public final class DbSmokeCheckApplication {
  private DbSmokeCheckApplication() {
  }

  public static void main(String[] args) {
    AppConfig config = AppConfig.fromEnv();
    DataSource dataSource = DataSourceFactory.create(config);
    QueueRepository repository = new QueueRepository(dataSource);
    try {
      DashboardSnapshot snapshot = repository.fetchDashboard();
      System.out.println("Dashboard consultado com sucesso: " + snapshot);
    } catch (Exception exception) {
      exception.printStackTrace(System.out);
      System.exit(1);
    }
  }
}