package studio.agent.platform.security;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class InternalWorkerScopeTest {
  @Test void scopes_credentials_and_artifacts_without_a_fallback_for_unknown_routes() {
    String id="00000000-0000-0000-0000-000000000001";
    assertEquals(InternalWorkerScope.AGENT, InternalWorkerScope.forPath("/internal/tasks/"+id+"/provider-credential", null));
    assertEquals(InternalWorkerScope.BROWSER, InternalWorkerScope.forPath("/internal/tasks/"+id+"/login-profiles/"+id+"/credential", null));
    assertEquals(InternalWorkerScope.AGENT, InternalWorkerScope.forPath("/internal/tasks/"+id+"/artifacts/presign", "HTML"));
    assertEquals(InternalWorkerScope.BROWSER, InternalWorkerScope.forPath("/internal/tasks/"+id+"/artifacts/presign", "SCREENSHOT"));
    assertEquals(InternalWorkerScope.AGENT, InternalWorkerScope.forPath("/internal/tasks/"+id+"/source-read/runs", null));
    assertEquals(InternalWorkerScope.AGENT, InternalWorkerScope.forPath("/internal/tasks/"+id+"/source-read/runs/"+id+"/chunks", null));
    assertEquals(InternalWorkerScope.AGENT, InternalWorkerScope.forPath("/internal/tasks/"+id+"/artifacts/presign", "DIAGRAM"));
    assertThrows(SecurityException.class, () -> InternalWorkerScope.forPath("/internal/unknown", null));
  }
}
