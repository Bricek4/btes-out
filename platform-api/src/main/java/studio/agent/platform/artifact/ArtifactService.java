package studio.agent.platform.artifact;

import java.io.OutputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import studio.agent.platform.security.CurrentUser;
import studio.agent.platform.storage.ObjectStoreService;

@Service
class ArtifactService {
  static final long MAX_PREVIEW_BYTES = 10L * 1024 * 1024;
  static final long MAX_REPLACEMENT_BYTES = 20_000_000L;
  static final long MAX_EXPORT_BYTES = 100L * 1024 * 1024;
  private static final int MAX_EXPORT_FILES = 500;

  private final JdbcClient jdbc;
  private final ObjectStoreService objects;

  ArtifactService(JdbcClient jdbc, ObjectStoreService objects) {
    this.jdbc = jdbc;
    this.objects = objects;
  }

  List<ArtifactTreeBuilder.Node> tree(CurrentUser user, UUID taskId) {
    requireTaskRead(user, taskId);
    var entries = jdbc.sql("""
        SELECT DISTINCT ON(a.name) a.id,a.name,a.kind,a.current_version,v.media_type,v.size_bytes,v.sha256
          FROM artifacts a
          JOIN artifact_versions v ON v.artifact_id=a.id AND v.ordinal=a.current_version
         WHERE a.task_id=:task AND a.current_version>0
         ORDER BY a.name,a.created_at DESC,a.id DESC
        """).param("task", taskId).query((rs, row) -> new ArtifactTreeBuilder.Entry(
        rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getInt(4),
        rs.getString(5), rs.getLong(6), rs.getString(7))).list();
    return ArtifactTreeBuilder.build(entries);
  }

  ArtifactDetail detail(CurrentUser user, UUID artifactId, Integer beforeVersion, int limit) {
    var artifact = readable(user, artifactId);
    if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
    if (beforeVersion != null && beforeVersion < 1) throw new IllegalArgumentException("beforeVersion must be positive");
    String cursor = beforeVersion == null ? "" : "AND ordinal<:before";
    var query = jdbc.sql("""
        SELECT ordinal,media_type,size_bytes,sha256,created_at
          FROM artifact_versions WHERE artifact_id=:id %s ORDER BY ordinal DESC LIMIT :limit
        """.formatted(cursor)).param("id", artifactId).param("limit", limit + 1);
    if (beforeVersion != null) query = query.param("before", beforeVersion);
    var fetched = query.query((rs, row) -> new VersionSummary(rs.getInt(1), rs.getString(2), rs.getLong(3),
        rs.getString(4), rs.getObject(5, OffsetDateTime.class))).list();
    boolean hasMore = fetched.size() > limit;
    var versions = hasMore ? List.copyOf(fetched.subList(0, limit)) : fetched;
    Integer nextBeforeVersion = hasMore ? versions.getLast().version() : null;
    return new ArtifactDetail(artifact.id(), artifact.taskId(), artifact.name(), artifact.kind(), artifact.currentVersion(),
        artifact.currentVersion(), nextBeforeVersion, versions);
  }

  VersionView versionDetail(CurrentUser user, UUID artifactId, int version) {
    readable(user, artifactId);
    return jdbc.sql("""
        SELECT ordinal,media_type,size_bytes,sha256,manifest::text,verification_report::text,created_at
          FROM artifact_versions WHERE artifact_id=:id AND ordinal=:version
        """).param("id", artifactId).param("version", version).query((rs, row) -> new VersionView(
        rs.getInt(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getString(5), rs.getString(6),
        rs.getObject(7, OffsetDateTime.class))).optional().orElseThrow(() -> notFound("ARTIFACT_VERSION_NOT_FOUND"));
  }

  StoredVersion readableVersion(CurrentUser user, UUID artifactId, Integer version) {
    readable(user, artifactId);
    String predicate = version == null ? "v.ordinal=a.current_version" : "v.ordinal=:version";
    var spec = jdbc.sql("""
        SELECT a.name,v.ordinal,v.object_key,v.media_type,v.size_bytes,v.sha256
          FROM artifacts a JOIN artifact_versions v ON v.artifact_id=a.id
         WHERE a.id=:id AND %s
        """.formatted(predicate)).param("id", artifactId);
    if (version != null) spec = spec.param("version", version);
    return spec.query((rs, row) -> new StoredVersion(rs.getString(1), rs.getInt(2), rs.getString(3),
        rs.getString(4), rs.getLong(5), rs.getString(6))).optional()
        .orElseThrow(() -> notFound("ARTIFACT_VERSION_NOT_FOUND"));
  }

  Preview preview(CurrentUser user, UUID artifactId, Integer version) {
    var stored = readableVersion(user, artifactId, version);
    if (stored.sizeBytes() > MAX_PREVIEW_BYTES) throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "ARTIFACT_PREVIEW_TOO_LARGE");
    return new Preview(stored, objects.get(stored.objectKey(), MAX_PREVIEW_BYTES));
  }

  ExportPlan exportPlan(CurrentUser user, UUID taskId) {
    requireTaskRead(user, taskId);
    var versions = jdbc.sql("""
        SELECT DISTINCT ON(a.name) a.id,a.name,v.object_key,v.size_bytes
          FROM artifacts a JOIN artifact_versions v ON v.artifact_id=a.id AND v.ordinal=a.current_version
         WHERE a.task_id=:task AND a.current_version>0
         ORDER BY a.name,a.created_at DESC,a.id DESC LIMIT :limit
        """).param("task", taskId).param("limit", MAX_EXPORT_FILES + 1).query((rs, row) ->
        new ExportVersion(rs.getObject(1, UUID.class), rs.getString(2), null, rs.getString(3), rs.getLong(4))).list();
    if (versions.size() > MAX_EXPORT_FILES) throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "ARTIFACT_EXPORT_TOO_MANY_FILES");
    long total = 0;
    var normalized = versions.stream().map(version -> new ExportVersion(version.artifactId(), version.name(),
        ArtifactZip.safeName(version.name()), version.objectKey(), version.sizeBytes())).toList();
    var folderPaths = new java.util.HashSet<String>();
    for (var version : normalized) {
      var parts = version.safeName().split("/", -1);
      var prefix = new StringBuilder();
      for (int index = 0; index < parts.length - 1; index++) {
        if (!prefix.isEmpty()) prefix.append('/');
        prefix.append(parts[index]);
        folderPaths.add(prefix.toString());
      }
    }
    var used = new java.util.HashSet<String>();
    var safeVersions = new java.util.ArrayList<ExportVersion>();
    for (var version : normalized) {
      try {
        total = Math.addExact(total, version.sizeBytes());
      } catch (ArithmeticException exception) {
        throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "ARTIFACT_EXPORT_TOO_LARGE");
      }
      if (total > MAX_EXPORT_BYTES) throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "ARTIFACT_EXPORT_TOO_LARGE");
      safeVersions.add(new ExportVersion(version.artifactId(), version.name(),
          ArtifactZip.uniqueName(version.safeName(), version.artifactId(), folderPaths, used),
          version.objectKey(), version.sizeBytes()));
    }
    return new ExportPlan(List.copyOf(safeVersions), total);
  }

  void writeExport(ExportPlan plan, OutputStream output) {
    try {
      var zip = new ZipOutputStream(output);
      for (var version : plan.versions()) {
        zip.putNextEntry(new ZipEntry(version.safeName()));
        long copied = objects.copyTo(version.objectKey(), version.sizeBytes(), zip);
        if (copied != version.sizeBytes()) throw new IllegalStateException("stored artifact size changed during export");
        zip.closeEntry();
      }
      zip.finish();
      zip.flush();
    } catch (java.io.IOException exception) {
      throw new IllegalStateException("artifact ZIP export failed", exception);
    }
  }

  @Transactional
  VersionView replaceImage(CurrentUser user, UUID artifactId, String mediaType, byte[] content) {
    if (content.length == 0 || content.length > MAX_REPLACEMENT_BYTES) throw new IllegalArgumentException("replacement image size is invalid");
    var artifact = ownedForUpdate(user, artifactId);
    if (!"SCREENSHOT".equals(artifact.kind())) throw new IllegalArgumentException("only screenshot artifacts can be replaced");
    String normalizedType = normalizeImageType(mediaType);
    String sha256 = sha256(content);
    var report = ArtifactVerifier.verify(normalizedType, content, content.length, sha256);
    if (!report.valid()) throw new IllegalArgumentException("replacement bytes do not match the declared image type");
    int ordinal = artifact.currentVersion() + 1;
    String objectKey = "artifacts/" + artifact.taskId() + "/" + artifact.id() + "/" + ordinal;
    objects.put(objectKey, content, normalizedType, sha256);
    var now = OffsetDateTime.now();
    String manifest = "{\"source\":\"USER_REPLACEMENT\"}";
    jdbc.sql("""
        INSERT INTO artifact_versions(id,artifact_id,ordinal,object_key,media_type,size_bytes,sha256,manifest,verification_report,created_at)
        VALUES(:id,:artifact,:ordinal,:key,:media,:size,:sha,CAST(:manifest AS jsonb),CAST(:report AS jsonb),:now)
        """).param("id", UUID.randomUUID()).param("artifact", artifactId).param("ordinal", ordinal)
        .param("key", objectKey).param("media", normalizedType).param("size", content.length).param("sha", sha256)
        .param("manifest", manifest).param("report", report.toJson()).param("now", now).update();
    jdbc.sql("UPDATE artifacts SET current_version=:ordinal WHERE id=:id")
        .param("ordinal", ordinal).param("id", artifactId).update();
    return new VersionView(ordinal, normalizedType, content.length, sha256, manifest, report.toJson(), now);
  }

  @Transactional
  ArtifactVerifier.Report verify(CurrentUser user, UUID artifactId) {
    var artifact = ownedForUpdate(user, artifactId);
    var version = jdbc.sql("""
        SELECT object_key,media_type,size_bytes,sha256 FROM artifact_versions
         WHERE artifact_id=:id AND ordinal=:ordinal
        """).param("id", artifactId).param("ordinal", artifact.currentVersion()).query((rs, row) ->
        new StoredVersion(artifact.name(), artifact.currentVersion(), rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4)))
        .optional().orElseThrow(() -> notFound("ARTIFACT_VERSION_NOT_FOUND"));
    if (version.sizeBytes() > MAX_REPLACEMENT_BYTES) {
      throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "ARTIFACT_VERIFICATION_TOO_LARGE");
    }
    byte[] content = objects.get(version.objectKey(), version.sizeBytes());
    var report = ArtifactVerifier.verify(version.mediaType(), content, version.sizeBytes(), version.sha256());
    jdbc.sql("UPDATE artifact_versions SET verification_report=CAST(:report AS jsonb) WHERE artifact_id=:id AND ordinal=:ordinal")
        .param("report", report.toJson()).param("id", artifactId).param("ordinal", artifact.currentVersion()).update();
    return report;
  }

  java.net.URL downloadUrl(StoredVersion version) {
    return objects.presignDownload(version.objectKey(), version.name(), Duration.ofMinutes(10));
  }

  private ArtifactRow readable(CurrentUser user, UUID artifactId) {
    return jdbc.sql("""
        SELECT a.id,a.task_id,a.name,a.kind,a.current_version
          FROM artifacts a JOIN tasks t ON t.id=a.task_id JOIN projects p ON p.id=t.project_id
          JOIN users owner_user ON owner_user.id=t.owner_id
         WHERE a.id=:id AND t.deleted_at IS NULL AND p.organization_id=:org AND p.owner_id=t.owner_id
           AND owner_user.organization_id=:org AND owner_user.disabled_at IS NULL AND (
               t.owner_id=:user
            OR EXISTS(SELECT 1 FROM shares s WHERE s.member_id=:user AND s.owner_id=t.owner_id AND s.resource_type='PROJECT' AND s.resource_id=t.project_id)
            OR EXISTS(SELECT 1 FROM shares s WHERE s.member_id=:user AND s.owner_id=t.owner_id AND s.resource_type='ARTIFACT' AND s.resource_id=a.id))
        """).param("id", artifactId).param("user", user.id()).param("org", user.organizationId()).query((rs, row) -> new ArtifactRow(
        rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getInt(5))).optional()
        .orElseThrow(() -> notFound("ARTIFACT_NOT_FOUND"));
  }

  private ArtifactRow ownedForUpdate(CurrentUser user, UUID artifactId) {
    return jdbc.sql("""
        SELECT a.id,a.task_id,a.name,a.kind,a.current_version
          FROM artifacts a JOIN tasks t ON t.id=a.task_id JOIN projects p ON p.id=t.project_id
         WHERE a.id=:id AND t.owner_id=:owner AND p.owner_id=:owner AND p.organization_id=:org
           AND t.deleted_at IS NULL FOR UPDATE OF a
        """).param("id", artifactId).param("owner", user.id()).param("org", user.organizationId()).query((rs, row) -> new ArtifactRow(
        rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getInt(5))).optional()
        .orElseThrow(() -> notFound("ARTIFACT_NOT_FOUND"));
  }

  private void requireTaskRead(CurrentUser user, UUID taskId) {
    boolean allowed = jdbc.sql("""
        SELECT EXISTS(SELECT 1 FROM tasks t JOIN projects p ON p.id=t.project_id JOIN users owner_user ON owner_user.id=t.owner_id
          WHERE t.id=:task AND t.deleted_at IS NULL AND p.organization_id=:org AND p.owner_id=t.owner_id
            AND owner_user.organization_id=:org AND owner_user.disabled_at IS NULL AND (
              t.owner_id=:user OR EXISTS(SELECT 1 FROM shares s WHERE s.member_id=:user AND s.owner_id=t.owner_id
                AND s.resource_type='PROJECT' AND s.resource_id=t.project_id)))
        """).param("task", taskId).param("user", user.id()).param("org", user.organizationId()).query(Boolean.class).single();
    if (!allowed) throw notFound("TASK_NOT_FOUND");
  }

  private static String normalizeImageType(String mediaType) {
    String normalized = mediaType == null ? "" : mediaType.toLowerCase(java.util.Locale.ROOT).split(";", 2)[0].trim();
    if (!List.of("image/png", "image/jpeg", "image/gif", "image/webp").contains(normalized)) {
      throw new IllegalArgumentException("unsupported replacement image type");
    }
    return normalized;
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static ResponseStatusException notFound(String code) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, code);
  }

  record ArtifactDetail(UUID artifactId, UUID taskId, String name, String kind, int currentVersion, int totalVersions,
                        Integer nextBeforeVersion, List<VersionSummary> versions) { }
  record VersionSummary(int version, String mediaType, long sizeBytes, String sha256, OffsetDateTime createdAt) { }
  record VersionView(int version, String mediaType, long sizeBytes, String sha256, String manifest,
                     String verificationReport, OffsetDateTime createdAt) { }
  record StoredVersion(String name, int version, String objectKey, String mediaType, long sizeBytes, String sha256) { }
  record Preview(StoredVersion version, byte[] content) { }
  private record ArtifactRow(UUID id, UUID taskId, String name, String kind, int currentVersion) { }
  record ExportPlan(List<ExportVersion> versions, long totalBytes) { }
  record ExportVersion(UUID artifactId, String name, String safeName, String objectKey, long sizeBytes) { }
}
