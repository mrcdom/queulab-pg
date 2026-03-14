package com.wedocode.queuelab.worker;

import com.wedocode.queuelab.core.AppConfig;
import com.wedocode.queuelab.core.DataSourceFactory;
import com.wedocode.queuelab.core.QueueRepository;
import javax.sql.DataSource;

public final class WorkerApplication {
  private WorkerApplication() {
  }

  public static void main(String[] args) {
    AppConfig config = AppConfig.fromEnv();
    DataSource dataSource = DataSourceFactory.create(config);
    QueueRepository repository = new QueueRepository(dataSource);
    WorkerRuntime runtime = new WorkerRuntime(dataSource, repository, config, new NotificationJobProcessor(config));

    Runtime.getRuntime().addShutdownHook(new Thread(runtime::stop));

    runtime.start();
    runtime.await();
  }
}