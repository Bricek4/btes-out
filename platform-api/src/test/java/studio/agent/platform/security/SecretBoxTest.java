package studio.agent.platform.security;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class SecretBoxTest {
  @Test
  void encrypts_with_random_nonces_and_authenticates_the_owner_context() {
    var key = Base64.getEncoder().encodeToString(new byte[32]);
    var box = new SecretBox(key);

    var first = box.encrypt("owner-1", "secret-value");
    var second = box.encrypt("owner-1", "secret-value");

    assertNotEquals(first, second);
    assertEquals("secret-value", box.decrypt("owner-1", first));
    assertThrows(IllegalArgumentException.class, () -> box.decrypt("owner-2", first));
    assertFalse(first.contains("secret-value"));
  }

  @Test
  void masks_keys_without_exposing_short_secrets() {
    assertEquals("********wxyz", SecretBox.mask("abcdefghwxyz"));
    assertEquals("********", SecretBox.mask("tiny"));
  }
}
