package studio.agent.platform.share;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ShareRulesTest {
  @Test
  void acceptsOnlyReadOnlyProjectAndArtifactSharesWithinOrganization() {
    var owner = UUID.randomUUID();
    var member = UUID.randomUUID();
    var organization = UUID.randomUUID();
    assertEquals("PROJECT", ShareRules.validate("project", owner, organization, member, organization));
    assertEquals("ARTIFACT", ShareRules.validate("ARTIFACT", owner, organization, member, organization));
    assertThrows(IllegalArgumentException.class,
        () -> ShareRules.validate("TASK", owner, organization, member, organization));
    assertThrows(IllegalArgumentException.class,
        () -> ShareRules.validate("PROJECT", owner, organization, owner, organization));
    assertThrows(IllegalArgumentException.class,
        () -> ShareRules.validate("PROJECT", owner, organization, member, UUID.randomUUID()));
  }
}
