package studio.agent.worker;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

class AgentWorkerApplicationTest {
  @Test void startsWithoutAStaticOpenAiCredentialBecauseProvidersAreTaskScoped() {
    try (var context = new SpringApplicationBuilder(AgentWorkerApplication.class)
        .web(WebApplicationType.NONE)
        .properties("AGENT_WORKER_TOKEN=test-token", "PLATFORM_API_URL=http://platform.test",
            "BROWSER_WORKER_URL=http://browser.test", "spring.main.banner-mode=off")
        .run()) {
      assertNotNull(context.getBean(WorkerExecutionService.class));
      assertNotNull(context.getBean(AgentPlatformClient.class));
    }
  }
}
