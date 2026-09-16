package studio.agent.platform.security;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class WorkerTokenGuardTest {
  @Test
  void accepts_only_the_token_for_its_worker_kind() {
    var guard = new WorkerTokenGuard("agent-secret", "browser-secret");
    assertDoesNotThrow(() -> guard.requireAgent("Bearer agent-secret"));
    assertDoesNotThrow(() -> guard.requireBrowser("Bearer browser-secret"));
    assertThrows(SecurityException.class, () -> guard.requireAgent("Bearer browser-secret"));
    assertThrows(SecurityException.class, () -> guard.requireBrowser("Bearer agent-secret"));
    assertThrows(SecurityException.class, () -> guard.requireAgent(null));
  }
}
