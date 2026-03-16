package com.wedocode.queuelab.worker;

import com.wedocode.queuelab.core.AppConfig;
import com.wedocode.queuelab.core.DataSourceFactory;
import com.wedocode.queuelab.core.QueueRepository;
import javax.sql.DataSource;

public final class WorkerApplication {
  private WorkerApplication() {
  }

  public static void main(String[] args) {
    var config = AppConfig.fromEnv();
    var dataSource = DataSourceFactory.create(config);
    var listenerDataSource = DataSourceFactory.createListener(config);
    var repository = new QueueRepository(dataSource);
    var runtime = new WorkerRuntime(listenerDataSource, new QueueRepositoryWorkerAdapter(repository), config, new NotificationJobProcessor(config));

    Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().name("worker-shutdown").unstarted(() -> {
      runtime.stop();
      closeQuietly(listenerDataSource);
      closeQuietly(dataSource);
    }));

    runtime.start();
    runtime.await();
  }

  private static void closeQuietly(DataSource dataSource) {
    if (dataSource instanceof AutoCloseable closeable) {
      try {
        closeable.close();
      } catch (Exception ignored) {
      }
    }
  }
}