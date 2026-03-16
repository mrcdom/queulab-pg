package com.wedocode.queuelab.worker;

import com.wedocode.queuelab.core.AppConfig;
import com.wedocode.queuelab.core.DataSourceFactory;
import com.wedocode.queuelab.core.QueueRepository;

public final class WorkerApplication {
  private WorkerApplication() {
  }

  public static void main(String[] args) {
    var config = AppConfig.fromEnv();
    var dataSource = DataSourceFactory.create(config);
    var repository = new QueueRepository(dataSource);

    if (config.useRabbitTransport()) {
      var publisher = new RabbitOutboxPublisher(dataSource, repository, config);
      var runtime = new RabbitWorkerRuntime(repository, config, new NotificationJobProcessor(config));
      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        runtime.stop();
        publisher.stop();
      }));
      publisher.start();
      runtime.start();
      runtime.await();
      return;
    }

    var runtime = new WorkerRuntime(dataSource, repository, config, new NotificationJobProcessor(config));
    Runtime.getRuntime().addShutdownHook(new Thread(runtime::stop));
    runtime.start();
    runtime.await();
  }
}