package studio.agent.workflow;

import java.net.URI;
import java.util.Objects;

/** Runtime-only configuration. Secrets are required from the environment and never have defaults. */
public record WorkflowRuntimeConfig(String temporalTarget, String temporalNamespace, String taskQueue,
                                    URI agentWorkerBaseUrl, URI platformApiBaseUrl, String agentWorkerToken,
                                    String workflowServiceToken) {
  public WorkflowRuntimeConfig {
    requireText(temporalTarget, "TEMPORAL_TARGET");
    requireText(temporalNamespace, "TEMPORAL_NAMESPACE");
    requireText(taskQueue, "TEMPORAL_TASK_QUEUE");
    Objects.requireNonNull(agentWorkerBaseUrl, "AGENT_WORKER_BASE_URL is required");
    if (!"http".equalsIgnoreCase(agentWorkerBaseUrl.getScheme())
        && !"https".equalsIgnoreCase(agentWorkerBaseUrl.getScheme())) {
      throw new IllegalArgumentException("AGENT_WORKER_BASE_URL must use HTTP(S)");
    }
    Objects.requireNonNull(platformApiBaseUrl, "PLATFORM_API_URL is required");
    if (!"http".equalsIgnoreCase(platformApiBaseUrl.getScheme())
        && !"https".equalsIgnoreCase(platformApiBaseUrl.getScheme())) {
      throw new IllegalArgumentException("PLATFORM_API_URL must use HTTP(S)");
    }
    requireText(agentWorkerToken, "AGENT_WORKER_TOKEN");
    if (workflowServiceToken == null || workflowServiceToken.isBlank()
        || workflowServiceToken.length() < 32) {
      throw new IllegalArgumentException("WORKFLOW_SERVICE_TOKEN must contain at least 32 characters");
    }
  }

  public static WorkflowRuntimeConfig fromEnvironment() {
    String target = env("TEMPORAL_TARGET", "127.0.0.1:7233");
    String namespace = env("TEMPORAL_NAMESPACE", "default");
    String queue = env("TEMPORAL_TASK_QUEUE", "agent-studio-tasks");
    String agentBase = env("AGENT_WORKER_BASE_URL", null);
    String agentToken = env("AGENT_WORKER_TOKEN", null);
    String workflowToken = env("WORKFLOW_SERVICE_TOKEN", null);
    String platformBase = env("PLATFORM_API_URL", null);
    if (agentBase == null || agentBase.isBlank()) throw new IllegalArgumentException("AGENT_WORKER_BASE_URL is required");
    if (platformBase == null || platformBase.isBlank()) throw new IllegalArgumentException("PLATFORM_API_URL is required");
    return new WorkflowRuntimeConfig(target, namespace, queue, URI.create(agentBase),
        URI.create(platformBase), agentToken, workflowToken);
  }

  private static String env(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value.trim();
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
  }
}
