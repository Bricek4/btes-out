package studio.agent.workflow;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.Test;

class WorkflowRuntimeConfigTest {
  @Test
  void requiresWorkerEndpointAndTokenAndRejectsNonHttpEndpoint() {
    assertThatThrownBy(() -> new WorkflowRuntimeConfig("127.0.0.1:7233", "default", "queue",
        URI.create("file:///tmp/worker"), "worker-token"))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("HTTP(S)");
    assertThatThrownBy(() -> new WorkflowRuntimeConfig("127.0.0.1:7233", "default", "queue",
        URI.create("http://agent-worker:8082"), " "))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("AGENT_WORKER_TOKEN");
  }
}
