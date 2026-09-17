package studio.agent.platform.security;

import java.util.Set;

public enum InternalWorkerScope { AGENT, BROWSER, EITHER;
  public static InternalWorkerScope forPath(String path, String artifactKind) {
    if (path.matches("/internal/worker-context/agent/[0-9a-fA-F-]+")
        || path.matches("/internal/tasks/[0-9a-fA-F-]+/(provider-credential|events)")
        || path.matches("/internal/tasks/[0-9a-fA-F-]+/source-read(?:/.*)?")) return AGENT;
    if (path.matches("/internal/worker-context/browser/[0-9a-fA-F-]+") || path.matches("/internal/tasks/[0-9a-fA-F-]+/login-profiles/[0-9a-fA-F-]+/credential")) return BROWSER;
    if (path.matches("/internal/tasks/[0-9a-fA-F-]+/artifacts/presign") || path.matches("/internal/tasks/[0-9a-fA-F-]+/artifacts/[0-9a-fA-F-]+/complete")) {
      if (artifactKind == null) return EITHER;
      return "SCREENSHOT".equals(artifactKind) ? BROWSER : Set.of("DOC", "HTML", "MANIFEST", "DIAGRAM").contains(artifactKind) ? AGENT : reject();
    }
    return reject();
  }
  private static InternalWorkerScope reject(){throw new SecurityException("WORKER_ROUTE_DENIED");}
}
