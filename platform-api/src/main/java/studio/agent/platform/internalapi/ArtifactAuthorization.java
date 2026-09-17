package studio.agent.platform.internalapi;

import java.util.Set;
import studio.agent.platform.security.WorkerTokenGuard;

final class ArtifactAuthorization {
  private ArtifactAuthorization() {}
  static void require(WorkerTokenGuard tokens,String authorization,String kind) {
    if ("SCREENSHOT".equals(kind)) { tokens.requireBrowser(authorization); return; }
    if (Set.of("DOC","HTML","MANIFEST","DIAGRAM").contains(kind)) { tokens.requireAgent(authorization); return; }
    throw new SecurityException("ARTIFACT_KIND_DENIED");
  }
}
