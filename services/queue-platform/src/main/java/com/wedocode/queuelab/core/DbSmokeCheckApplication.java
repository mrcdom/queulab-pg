package com.wedocode.queuelab.core;

public final class DbSmokeCheckApplication {
  private DbSmokeCheckApplication() {
  }

  public static void main(String[] args) {
    var config = AppConfig.fromEnv();
    var dataSource = DataSourceFactory.create(config);
    var repository = new QueueRepository(dataSource);
    try {
      var snapshot = repository.fetchDashboard();
      System.out.println("Dashboard consultado com sucesso: " + snapshot);
    } catch (Exception exception) {
      exception.printStackTrace(System.out);
      System.exit(1);
    }
  }
}