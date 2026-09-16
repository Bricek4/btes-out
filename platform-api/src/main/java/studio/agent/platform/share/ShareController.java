package studio.agent.platform.share;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import studio.agent.platform.security.CurrentUser;

@RestController
@RequestMapping("/api/v1")
public class ShareController {
  private final JdbcClient jdbc;

  public ShareController(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  record ShareInput(UUID memberId, String resourceType, UUID resourceId) { }
  record MemberView(UUID id, String email) { }
  record ShareView(UUID id, String resourceType, UUID resourceId, MemberView member,
                   String access, OffsetDateTime createdAt) { }
  record ReceivedShareView(UUID id, String resourceType, UUID resourceId, MemberView owner,
                           String access, OffsetDateTime createdAt) { }

  @GetMapping("/members")
  List<MemberView> members(CurrentUser user, @RequestParam(defaultValue = "") String query) {
    String normalized = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
    return jdbc.sql("""
        SELECT id,email FROM users
         WHERE organization_id=:org AND id<>:user AND disabled_at IS NULL AND lower(email) LIKE :query
         ORDER BY lower(email) LIMIT 50
        """).param("org", user.organizationId()).param("user", user.id()).param("query", "%" + escapeLike(normalized) + "%")
        .query((rs, row) -> new MemberView(rs.getObject(1, UUID.class), rs.getString(2))).list();
  }

  @GetMapping("/shares")
  List<ShareView> list(CurrentUser user, @RequestParam String resourceType, @RequestParam UUID resourceId) {
    var resource = ownedResource(user, resourceType, resourceId);
    return jdbc.sql("""
        SELECT s.id,s.resource_type,s.resource_id,u.id,u.email,s.created_at
          FROM shares s JOIN users u ON u.id=s.member_id
         WHERE s.owner_id=:owner AND s.resource_type=:type AND s.resource_id=:resource
         ORDER BY lower(u.email)
        """).param("owner", user.id()).param("type", resource.type()).param("resource", resourceId)
        .query((rs, row) -> new ShareView(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class),
            new MemberView(rs.getObject(4, UUID.class), rs.getString(5)), "READ", rs.getObject(6, OffsetDateTime.class))).list();
  }

  @GetMapping("/shares/received")
  List<ReceivedShareView> received(CurrentUser user) {
    return jdbc.sql("""
        SELECT s.id,s.resource_type,s.resource_id,u.id,u.email,s.created_at
          FROM shares s JOIN users u ON u.id=s.owner_id
         WHERE s.member_id=:member ORDER BY s.created_at DESC
        """).param("member", user.id()).query((rs, row) -> new ReceivedShareView(
        rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class),
        new MemberView(rs.getObject(4, UUID.class), rs.getString(5)), "READ", rs.getObject(6, OffsetDateTime.class))).list();
  }

  @PostMapping("/shares")
  @ResponseStatus(HttpStatus.CREATED)
  @Transactional
  ShareView create(CurrentUser user, @RequestBody ShareInput input) {
    if (input.memberId() == null || input.resourceId() == null) throw new IllegalArgumentException("memberId and resourceId are required");
    var resource = ownedResource(user, input.resourceType(), input.resourceId());
    var member = jdbc.sql("SELECT id,email,organization_id FROM users WHERE id=:id AND disabled_at IS NULL")
        .param("id", input.memberId()).query((rs, row) -> new Member(
            rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class))).optional()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND"));
    ShareRules.validate(resource.type(), user.id(), user.organizationId(), member.id(), member.organizationId());
    var id = UUID.randomUUID();
    var now = OffsetDateTime.now();
    int inserted = jdbc.sql("""
        INSERT INTO shares(id,owner_id,member_id,resource_type,resource_id,created_at)
        VALUES(:id,:owner,:member,:type,:resource,:now)
        ON CONFLICT(member_id,resource_type,resource_id) DO NOTHING
        """).param("id", id).param("owner", user.id()).param("member", member.id()).param("type", resource.type())
        .param("resource", input.resourceId()).param("now", now).update();
    if (inserted == 0) {
      var existing = jdbc.sql("SELECT id,created_at FROM shares WHERE member_id=:member AND resource_type=:type AND resource_id=:resource AND owner_id=:owner")
          .param("member", member.id()).param("type", resource.type()).param("resource", input.resourceId()).param("owner", user.id())
          .query((rs, row) -> new ExistingShare(rs.getObject(1, UUID.class), rs.getObject(2, OffsetDateTime.class))).optional()
          .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "RESOURCE_ALREADY_SHARED"));
      id = existing.id();
      now = existing.createdAt();
    }
    return new ShareView(id, resource.type(), input.resourceId(), new MemberView(member.id(), member.email()), "READ", now);
  }

  @DeleteMapping("/shares/{shareId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void delete(CurrentUser user, @PathVariable UUID shareId) {
    if (jdbc.sql("DELETE FROM shares WHERE id=:id AND owner_id=:owner").param("id", shareId).param("owner", user.id()).update() != 1) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "SHARE_NOT_FOUND");
    }
  }

  private OwnedResource ownedResource(CurrentUser user, String type, UUID resourceId) {
    String normalized = type == null ? "" : type.trim().toUpperCase(java.util.Locale.ROOT);
    String sql = switch (normalized) {
      case "PROJECT" -> "SELECT owner_id,organization_id FROM projects WHERE id=:id";
      case "ARTIFACT" -> "SELECT t.owner_id,p.organization_id FROM artifacts a JOIN tasks t ON t.id=a.task_id JOIN projects p ON p.id=t.project_id WHERE a.id=:id AND t.deleted_at IS NULL";
      default -> throw new IllegalArgumentException("resourceType must be PROJECT or ARTIFACT");
    };
    var resource = jdbc.sql(sql).param("id", resourceId).query((rs, row) -> new ResourceOwner(
        rs.getObject(1, UUID.class), rs.getObject(2, UUID.class))).optional()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND"));
    if (!resource.ownerId().equals(user.id()) || !resource.organizationId().equals(user.organizationId())) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
    }
    return new OwnedResource(normalized);
  }

  private static String escapeLike(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  private record Member(UUID id, String email, UUID organizationId) { }
  private record ResourceOwner(UUID ownerId, UUID organizationId) { }
  private record OwnedResource(String type) { }
  private record ExistingShare(UUID id, OffsetDateTime createdAt) { }
}
