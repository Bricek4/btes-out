package studio.agent.platform.share;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

final class ShareRules {
  private static final Set<String> RESOURCE_TYPES = Set.of("PROJECT", "ARTIFACT");

  private ShareRules() { }

  static String validate(String resourceType, UUID ownerId, UUID ownerOrganization,
                         UUID memberId, UUID memberOrganization) {
    String normalized = resourceType == null ? "" : resourceType.trim().toUpperCase(Locale.ROOT);
    if (!RESOURCE_TYPES.contains(normalized)) throw new IllegalArgumentException("resourceType must be PROJECT or ARTIFACT");
    if (ownerId.equals(memberId)) throw new IllegalArgumentException("resource cannot be shared with its owner");
    if (!ownerOrganization.equals(memberOrganization)) throw new IllegalArgumentException("member must belong to the same organization");
    return normalized;
  }
}
