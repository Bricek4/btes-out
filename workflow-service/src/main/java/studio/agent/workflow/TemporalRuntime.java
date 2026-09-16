package studio.agent.workflow;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Owns the Temporal client and worker lifecycle for this deployable service. */
public final class TemporalRuntime implements AutoCloseable {
  private final WorkflowServiceStubs service;
  private final WorkflowClient client;
  private final WorkerFactory factory;

  private TemporalRuntime(WorkflowServiceStubs service, WorkflowClient client, WorkerFactory factory) {
    this.service = service;
    this.client = client;
    this.factory = factory;
  }

  public static TemporalRuntime connect(WorkflowRuntimeConfig config, TaskActivities activities) {
    Objects.requireNonNull(config, "config is required");
    Objects.requireNonNull(activities, "activities are required");
    WorkflowServiceStubs service = WorkflowServiceStubs.newServiceStubs(
        WorkflowServiceStubsOptions.newBuilder().setTarget(config.temporalTarget()).build());
    WorkflowClient client = WorkflowClient.newInstance(service,
        WorkflowClientOptions.newBuilder().setNamespace(config.temporalNamespace()).build());
    WorkerFactory factory = WorkerFactory.newInstance(client);
    Worker worker = factory.newWorker(config.taskQueue());
    worker.registerWorkflowImplementationTypes(TaskWorkflowImpl.class);
    worker.registerActivitiesImplementations(activities);
    return new TemporalRuntime(service, client, factory);
  }

  public WorkflowClient client() { return client; }

  public synchronized void start() {
    if (!factory.isStarted()) factory.start();
  }

  @Override
  public synchronized void close() {
    if (!factory.isShutdown()) {
      factory.shutdown();
      factory.awaitTermination(30, TimeUnit.SECONDS);
    }
    service.shutdown();
  }
}
