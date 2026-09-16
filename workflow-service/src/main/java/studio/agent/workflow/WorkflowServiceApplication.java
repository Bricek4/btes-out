package studio.agent.workflow;

import java.util.concurrent.CountDownLatch;

/** Standalone Temporal worker entry point used by the workflow-service container. */
public final class WorkflowServiceApplication {
  private WorkflowServiceApplication() {}

  public static void main(String[] args) throws InterruptedException {
    WorkflowRuntimeConfig config = WorkflowRuntimeConfig.fromEnvironment();
    TemporalRuntime runtime = TemporalRuntime.connect(config,
        new HttpTaskActivities(config.agentWorkerBaseUrl(), config.agentWorkerToken()));
    Runtime.getRuntime().addShutdownHook(new Thread(runtime::close, "workflow-runtime-shutdown"));
    runtime.start();
    new CountDownLatch(1).await();
  }
}
