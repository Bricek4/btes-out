package studio.agent.platform.config;

import java.util.Set;

public final class RequiredPlatformSettings {
  private static final Set<String> PLACEHOLDERS = Set.of("change-me-before-first-run", "change-agent-token", "change-browser-token", "minioadmin", "agent_studio", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
  private RequiredPlatformSettings() {}
  public static String require(String name, String value) {
    if (value == null || value.isBlank() || PLACEHOLDERS.contains(value.trim())) throw new IllegalStateException(name + " must be configured securely");
    return value;
  }
}
