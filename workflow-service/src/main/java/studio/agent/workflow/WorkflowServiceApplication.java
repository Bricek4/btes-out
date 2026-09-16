package studio.agent.workflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/** Deployable HTTP bridge and Temporal worker process. */
@SpringBootApplication
public class WorkflowServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(WorkflowServiceApplication.class, args);
  }

  @Bean
  WorkflowRuntimeConfig workflowRuntimeConfig() {
    return WorkflowRuntimeConfig.fromEnvironment();
  }

  @Bean
  PlatformStatusReporter platformStatusReporter(WorkflowRuntimeConfig config) {
    return new HttpPlatformStatusReporter(config.platformApiBaseUrl(), config.agentWorkerToken());
  }

  @Bean
  TaskActivities taskActivities(WorkflowRuntimeConfig config, PlatformStatusReporter reporter) {
    return new HttpTaskActivities(config.agentWorkerBaseUrl(), config.agentWorkerToken(), reporter);
  }

  @Bean(initMethod = "start", destroyMethod = "close")
  TemporalRuntime temporalRuntime(WorkflowRuntimeConfig config, TaskActivities activities) {
    return TemporalRuntime.connect(config, activities);
  }

  @Bean
  WorkflowTaskGateway workflowTaskGateway(TemporalRuntime runtime, WorkflowRuntimeConfig config) {
    return new TemporalWorkflowTaskGateway(runtime.client(), config.taskQueue());
  }

  @Bean
  WorkflowServiceTokenFilter workflowServiceTokenFilter(WorkflowRuntimeConfig config) {
    return new WorkflowServiceTokenFilter(config.workflowServiceToken());
  }
}
