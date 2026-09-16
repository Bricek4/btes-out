package studio.agent.platform.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Separates the two worker trust domains; a Browser token can never call Agent routes. */
public final class WorkerTokenGuard {
  private final String agentToken;
  private final String browserToken;

  public WorkerTokenGuard(String agentToken, String browserToken) {
    this.agentToken = agentToken;
    this.browserToken = browserToken;
  }

  public void requireAgent(String authorization) { require(authorization, agentToken); }
  public void requireBrowser(String authorization) { require(authorization, browserToken); }

  private static void require(String authorization, String expected) {
    var supplied = authorization != null && authorization.startsWith("Bearer ")
        ? authorization.substring(7) : "";
    if (expected == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
      throw new SecurityException("WORKER_TOKEN_INVALID");
    }
  }
}
