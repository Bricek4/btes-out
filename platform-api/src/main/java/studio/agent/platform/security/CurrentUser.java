package studio.agent.platform.security;

import java.security.Principal;
import java.util.UUID;

public record CurrentUser(UUID id, UUID organizationId, String email, String role) implements Principal {
  @Override public String getName() { return id.toString(); }
  public boolean admin() { return "ADMIN".equals(role); }
}
