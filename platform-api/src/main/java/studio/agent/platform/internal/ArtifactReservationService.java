package studio.agent.platform.internalapi;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import studio.agent.platform.artifact.ArtifactZip;
import studio.agent.platform.security.WorkerTokenGuard;
import studio.agent.platform.storage.ObjectStoreService;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Transactional reservation and publication boundary shared by Agent and Browser workers. */
@Service
public class ArtifactReservationService {
  static final long MAX_ARTIFACT_BYTES = 20_000_000L;
  static final long MAX_MANIFEST_BYTES = 1_000_000L;
  private static final Set<String> AGENT_KINDS = Set.of("DOC", "HTML", "MANIFEST");
  private static final ObjectMapper JSON = new ObjectMapper();

  private final JdbcClient jdbc;
  private final WorkerTokenGuard tokens;
  private final ObjectStoreService objects;

  public ArtifactReservationService(JdbcClient jdbc, WorkerTokenGuard tokens, ObjectStoreService objects) {
    this.jdbc = jdbc;
    this.tokens = tokens;
    this.objects = objects;
  }

  @Transactional
  public Map<String, Object> presign(String authorization, UUID taskId, PresignRequest request) {
    requireTaskLock(taskId);
    validateRequest(request);
    ArtifactAuthorization.require(tokens, authorization, request.kind());
    var existing = jdbc.sql("""
        SELECT r.id,r.object_key,r.media_type,r.expected_size,r.expected_sha256,r.idempotency_key,
               a.id,a.name,a.kind
          FROM object_reservations r
          JOIN artifacts a ON a.id=r.artifact_id
         WHERE r.task_id=:task AND r.idempotency_key=:key
         FOR UPDATE OF r,a
        """)
        .param("task", taskId).param("key", request.idempotencyKey())
        .query((rs, n) -> new ReservationRow(
            rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getLong(4),
            rs.getString(5), rs.getString(6), rs.getObject(7, UUID.class), rs.getString(8), rs.getString(9),
            0, null, OffsetDateTime.MAX))
        .optional();
    if (existing.isPresent()) {
      ReservationRow row = existing.get();
      if (!row.matches(request)) throw new IllegalArgumentException("artifact idempotency key payload mismatch");
      return reservationResponse(taskId, row.artifactId(), row.reservationId(), row.objectKey(), row.mediaType(),
          row.size(), row.sha256(), row.idempotencyKey());
    }

    UUID artifactId = UUID.randomUUID();
    UUID reservationId = UUID.randomUUID();
    String key = "artifacts/" + taskId + "/" + artifactId + "/1";
    OffsetDateTime now = OffsetDateTime.now();
    jdbc.sql("INSERT INTO artifacts(id,task_id,name,kind,current_version,created_at) VALUES(:id,:task,:name,:kind,0,:now)")
        .param("id", artifactId).param("task", taskId).param("name", request.name()).param("kind", request.kind()).param("now", now).update();
    jdbc.sql("""
        INSERT INTO object_reservations(id,task_id,artifact_id,object_key,media_type,expected_size,expected_sha256,
                                        expires_at,idempotency_key)
        VALUES(:id,:task,:artifact,:key,:media,:size,:sha,:expires,:idempotency)
        """)
        .param("id", reservationId).param("task", taskId).param("artifact", artifactId).param("key", key)
        .param("media", request.mediaType()).param("size", request.sizeBytes()).param("sha", request.sha256())
        .param("expires", now.plusMinutes(10)).param("idempotency", request.idempotencyKey()).update();
    return reservationResponse(taskId, artifactId, reservationId, key, request.mediaType(), request.sizeBytes(),
        request.sha256(), request.idempotencyKey());
  }

  @Transactional
  public Map<String, Object> complete(String authorization, UUID taskId, UUID artifactId, CompletionRequest request) {
    requireTaskLock(taskId);
    UUID reservationId = parseReservationId(request == null ? null : request.reservationId());
    ReservationRow reservation = jdbc.sql("""
        SELECT r.id,r.object_key,r.media_type,r.expected_size,r.expected_sha256,r.idempotency_key,
               a.id,a.name,a.kind,a.current_version,r.completed_at,r.expires_at
          FROM object_reservations r
          JOIN artifacts a ON a.id=r.artifact_id
         WHERE r.id=:reservation AND r.task_id=:task AND r.artifact_id=:artifact
         FOR UPDATE OF r,a
        """)
        .param("reservation", reservationId).param("task", taskId).param("artifact", artifactId)
        .query((rs, n) -> new ReservationRow(
            rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getString(5),
            rs.getString(6), rs.getObject(7, UUID.class), rs.getString(8), rs.getString(9),
            rs.getInt(10), rs.getObject(11, OffsetDateTime.class), rs.getObject(12, OffsetDateTime.class)))
        .optional().orElseThrow(() -> new IllegalArgumentException("artifact reservation not found"));
    ArtifactAuthorization.require(tokens, authorization, reservation.kind());
    if (reservation.completedAt() != null) {
      var result = new java.util.LinkedHashMap<String, Object>();
      result.put("artifactId", artifactId);
      result.put("completed", true);
      result.put("version", reservation.currentVersion());
      if (reservation.idempotencyKey() != null) result.put("idempotencyKey", reservation.idempotencyKey());
      return Map.copyOf(result);
    }
    if (!reservation.expiresAt().isAfter(OffsetDateTime.now())) throw new IllegalArgumentException("artifact reservation expired");
    String manifest = validateManifest(request == null ? null : request.manifest());
    var head = objects.head(reservation.objectKey());
    String expectedChecksum = java.util.Base64.getEncoder().encodeToString(
        java.util.HexFormat.of().parseHex(reservation.sha256()));
    if (head.size() != reservation.size() || !expectedChecksum.equals(head.checksumSha256())) {
      throw new IllegalArgumentException("artifact checksum or size mismatch");
    }
    int ordinal = reservation.currentVersion() + 1;
    OffsetDateTime now = OffsetDateTime.now();
    jdbc.sql("""
        INSERT INTO artifact_versions(id,artifact_id,ordinal,object_key,media_type,size_bytes,sha256,manifest,
                                      verification_report,created_at)
        VALUES(:id,:artifact,:ordinal,:key,:media,:size,:sha,CAST(:manifest AS jsonb),'{}'::jsonb,:now)
        """)
        .param("id", UUID.randomUUID()).param("artifact", artifactId).param("ordinal", ordinal)
        .param("key", reservation.objectKey()).param("media", reservation.mediaType()).param("size", reservation.size())
        .param("sha", reservation.sha256()).param("manifest", manifest).param("now", now).update();
    jdbc.sql("UPDATE artifacts SET current_version=:version WHERE id=:id")
        .param("version", ordinal).param("id", artifactId).update();
    jdbc.sql("UPDATE object_reservations SET completed_at=:now WHERE id=:id AND completed_at IS NULL")
        .param("now", now).param("id", reservation.reservationId()).update();
    var result = new java.util.LinkedHashMap<String, Object>();
    result.put("artifactId", artifactId);
    result.put("completed", true);
    result.put("version", ordinal);
    if (reservation.idempotencyKey() != null) result.put("idempotencyKey", reservation.idempotencyKey());
    return Map.copyOf(result);
  }

  private void requireTaskLock(UUID taskId) {
    jdbc.sql("SELECT id FROM tasks WHERE id=:id AND deleted_at IS NULL FOR UPDATE")
        .param("id", taskId).query(UUID.class).optional().orElseThrow(() -> new IllegalArgumentException("task not found"));
  }

  private Map<String, Object> reservationResponse(UUID taskId, UUID artifactId, UUID reservationId, String objectKey,
      String mediaType, long size, String sha256, String idempotencyKey) {
    return Map.of("artifactId", artifactId, "reservationId", reservationId, "idempotencyKey", idempotencyKey,
        "putUrl", objects.presignPut(objectKey, mediaType, size, sha256, Duration.ofMinutes(10)).toString());
  }

  private static void validateRequest(PresignRequest request) {
    if (request == null || request.name() == null || request.name().isBlank() || request.name().length() > 255
        || request.name().startsWith("/") || request.name().contains("\\") || request.name().contains("\0")
        || Arrays.asList(request.name().split("/")).contains("..")) throw new IllegalArgumentException("invalid artifact name");
    if (!ArtifactZip.safeName(request.name()).equals(request.name())) throw new IllegalArgumentException("artifact name must be canonical");
    if (request.kind() == null || !(AGENT_KINDS.contains(request.kind()) || "SCREENSHOT".equals(request.kind()))) {
      throw new IllegalArgumentException("invalid artifact kind");
    }
    if (request.mediaType() == null || request.mediaType().isBlank() || request.mediaType().length() > 255
        || request.sizeBytes() < 0 || request.sizeBytes() > MAX_ARTIFACT_BYTES
        || request.sha256() == null || !request.sha256().matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("invalid artifact reservation");
    }
    if (request.idempotencyKey() == null || !request.idempotencyKey().matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("idempotencyKey must be a lowercase SHA-256");
    }
  }

  private static String validateManifest(String manifest) {
    String value = manifest == null || manifest.isBlank() ? "{}" : manifest;
    if (value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_MANIFEST_BYTES) {
      throw new IllegalArgumentException("artifact manifest is too large");
    }
    try {
      JsonNode node = JSON.readTree(value);
      if (node == null || !node.isObject()) throw new IllegalArgumentException("artifact manifest must be an object");
    } catch (JacksonException invalid) {
      throw new IllegalArgumentException("artifact manifest is invalid");
    }
    return value;
  }

  private static UUID parseReservationId(String value) {
    try { return UUID.fromString(value); }
    catch (Exception invalid) { throw new IllegalArgumentException("reservationId is required"); }
  }

  public record PresignRequest(String name, String kind, String mediaType, long sizeBytes, String sha256,
                               String idempotencyKey) { }
  public record CompletionRequest(String reservationId, String manifest) { }

  private record ReservationRow(UUID reservationId, String objectKey, String mediaType, long size, String sha256,
                                String idempotencyKey, UUID artifactId, String name, String kind,
                                int currentVersion, OffsetDateTime completedAt, OffsetDateTime expiresAt) {
    boolean matches(PresignRequest request) {
      return name.equals(request.name()) && kind.equals(request.kind()) && mediaType.equals(request.mediaType())
          && size == request.sizeBytes() && sha256.equals(request.sha256()) && idempotencyKey.equals(request.idempotencyKey());
    }
  }
}
