package studio.agent.platform.config;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class RequiredPlatformSettingsTest {
  @Test void rejects_blank_and_known_placeholder_security_values() {
    assertThrows(IllegalStateException.class, () -> RequiredPlatformSettings.require("SETUP_TOKEN", " "));
    assertThrows(IllegalStateException.class, () -> RequiredPlatformSettings.require("AGENT_WORKER_TOKEN", "change-agent-token"));
    assertThrows(IllegalStateException.class, () -> RequiredPlatformSettings.require("S3_SECRET_KEY", "minioadmin"));
    assertEquals("real-value", RequiredPlatformSettings.require("X", "real-value"));
  }
}
