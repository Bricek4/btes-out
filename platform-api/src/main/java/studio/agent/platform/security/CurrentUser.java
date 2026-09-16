package studio.agent.platform.security;

import java.util.UUID;

/** Authenticated application identity used by the explicit MVC argument resolver. */
public record CurrentUser(UUID id, UUID organizationId, String email, String role) {
  public boolean admin() { return "ADMIN".equals(role); }
}
