package studio.agent.platform.internalapi;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import studio.agent.platform.security.WorkerTokenGuard;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Agent-scoped checkpoint API; source bytes never cross this boundary. */
@RestController
@RequestMapping("/internal/tasks/{taskId}/source-read")
final class SourceReadController {
  private static final int MAX_CHUNKS_PER_REQUEST = 64;
  private static final int MAX_SUMMARY_BYTES = 15_000;
  private static final ObjectMapper JSON = new ObjectMapper();

  private final JdbcClient jdbc;
  private final WorkerTokenGuard tokens;

  SourceReadController(JdbcClient jdbc, WorkerTokenGuard tokens) {
    this.jdbc = jdbc;
    this.tokens = tokens;
  }

  @PostMapping("/runs")
  ResponseEntity<Map<String, Object>> createRun(@RequestHeader("Authorization") String authorization,
      @PathVariable UUID taskId, @RequestBody RunRequest request) {
    tokens.requireAgent(authorization);
    requireTask(taskId);
    if (request == null) throw new IllegalArgumentException("source read run is required");
    requireSha(request.revisionSha256());
    String scope = required(request.scope(), "scope", 64);
    if (request.totalEntries() < 0) throw new IllegalArgumentException("totalEntries is invalid");
    var existing = jdbc.sql("SELECT id,revision_sha256,scope,status FROM source_read_runs WHERE task_id=:task")
        .param("task", taskId).query((rs, row) -> new RunRow(rs.getObject(1, UUID.class), rs.getString(2),
            rs.getString(3), rs.getString(4))).optional();
    if (existing.isPresent()) {
      RunRow row = existing.get();
      if (!row.revisionSha256().equals(request.revisionSha256()) || !row.scope().equals(scope)) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "SOURCE_READ_RUN_CONFLICT");
      }
      return noStore(Map.of("runId", row.id(), "status", row.status()));
    }
    UUID runId = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now();
    jdbc.sql("""
        INSERT INTO source_read_runs(id,task_id,revision_sha256,scope,status,total_entries,created_at,updated_at)
        VALUES(:id,:task,:sha,:scope,'PLANNED',:entries,:now,:now)
        """).param("id", runId).param("task", taskId).param("sha", request.revisionSha256())
        .param("scope", scope).param("entries", request.totalEntries()).param("now", now).update();
    return noStore(Map.of("runId", runId, "status", "PLANNED"));
  }

  @PostMapping("/runs/{runId}/files")
  ResponseEntity<Void> registerFile(@RequestHeader("Authorization") String authorization,
      @PathVariable UUID taskId, @PathVariable UUID runId, @RequestBody FileRequest request) {
    tokens.requireAgent(authorization);
    requireRun(taskId, runId);
    if (request == null) throw new IllegalArgumentException("source read file is required");
    String path = canonicalPath(request.path());
    String category = enumValue(request.category(), Set.of("SOURCE", "CONFIG", "DOCUMENT", "GENERATED", "DEPENDENCY", "BINARY"), "category");
    String status = enumValue(request.status(), Set.of("PLANNED", "SKIPPED"), "status");
    requireSha(request.sha256());
    if (request.sizeBytes() < 0 || request.chunkCount() < 0) throw new IllegalArgumentException("source read file limits are invalid");
    if (status.equals("SKIPPED") && (request.skipReason() == null || request.skipReason().isBlank())) {
      throw new IllegalArgumentException("skipped source read files require a reason");
    }
    jdbc.sql("""
        INSERT INTO source_read_files(id,run_id,path,category,size_bytes,sha256,chunk_count,status,skip_reason,created_at,updated_at)
        VALUES(:id,:run,:path,:category,:size,:sha,:chunks,:status,:reason,:now,:now)
        ON CONFLICT(run_id,path) DO UPDATE SET category=EXCLUDED.category,size_bytes=EXCLUDED.size_bytes,
          sha256=EXCLUDED.sha256,chunk_count=EXCLUDED.chunk_count,updated_at=EXCLUDED.updated_at
        """).param("id", UUID.randomUUID()).param("run", runId).param("path", path).param("category", category)
        .param("size", request.sizeBytes()).param("sha", request.sha256()).param("chunks", request.chunkCount())
        .param("status", status).param("reason", request.skipReason()).param("now", OffsetDateTime.now()).update();
    refreshRunCounts(runId);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/runs/{runId}/chunks")
  ResponseEntity<Void> registerChunks(@RequestHeader("Authorization") String authorization,
      @PathVariable UUID taskId, @PathVariable UUID runId, @RequestBody ChunkBatch request) {
    tokens.requireAgent(authorization);
    requireRun(taskId, runId);
    if (request == null || request.chunks() == null || request.chunks().isEmpty()
        || request.chunks().size() > MAX_CHUNKS_PER_REQUEST) {
      throw new IllegalArgumentException("source read chunk batch is invalid");
    }
    var seen = new HashSet<String>();
    for (ChunkRequest chunk : request.chunks()) {
      if (chunk == null || !seen.add(chunk.chunkId())) throw new IllegalArgumentException("source read chunk is duplicated");
      String path = canonicalPath(chunk.filePath());
      requireSha(chunk.chunkSha256());
      if (chunk.ordinal() < 0 || chunk.startOffset() < 0 || chunk.endOffset() < chunk.startOffset()) {
        throw new IllegalArgumentException("source read chunk offsets are invalid");
      }
      UUID fileId = jdbc.sql("SELECT id FROM source_read_files WHERE id IS NOT NULL AND run_id=:run AND path=:path")
          .param("run", runId).param("path", path).query(UUID.class).optional()
          .orElseThrow(() -> new IllegalArgumentException("source read file is not registered"));
      jdbc.sql("""
          INSERT INTO source_read_chunks(id,run_id,file_id,chunk_id,file_path,ordinal,start_offset,end_offset,chunk_sha256,status,created_at,updated_at)
          VALUES(:id,:run,:file,:chunk,:path,:ordinal,:start,:end,:sha,'PLANNED',:now,:now)
          ON CONFLICT(run_id,chunk_id) DO UPDATE SET file_id=EXCLUDED.file_id,file_path=EXCLUDED.file_path,
            ordinal=EXCLUDED.ordinal,start_offset=EXCLUDED.start_offset,end_offset=EXCLUDED.end_offset,
            chunk_sha256=EXCLUDED.chunk_sha256,updated_at=EXCLUDED.updated_at
          """).param("id", UUID.randomUUID()).param("run", runId).param("file", fileId).param("chunk", chunk.chunkId())
          .param("path", path).param("ordinal", chunk.ordinal()).param("start", chunk.startOffset())
          .param("end", chunk.endOffset()).param("sha", chunk.chunkSha256()).param("now", OffsetDateTime.now()).update();
    }
    refreshRunCounts(runId);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/runs/{runId}/coverage")
  ResponseEntity<Map<String, Object>> coverage(@RequestHeader("Authorization") String authorization,
      @PathVariable UUID taskId, @PathVariable UUID runId) {
    tokens.requireAgent(authorization);
    RunRow run = requireRun(taskId, runId);
    var completed = jdbc.sql("SELECT chunk_id FROM source_read_chunks WHERE run_id=:run AND status='ANALYZED' ORDER BY file_path,ordinal")
        .param("run", runId).query(String.class).list();
    var failed = jdbc.sql("SELECT chunk_id,failure_code FROM source_read_chunks WHERE run_id=:run AND status='FAILED' ORDER BY file_path,ordinal")
        .param("run", runId).query((rs, row) -> Map.of("chunkId", rs.getString(1), "failureCode", rs.getString(2))).list();
    var files = jdbc.sql("SELECT path,category,size_bytes,sha256,chunk_count,analyzed_chunk_count,status,skip_reason FROM source_read_files WHERE run_id=:run ORDER BY path")
        .param("run", runId).query((rs, row) -> {
          var value = new LinkedHashMap<String, Object>();
          value.put("path", rs.getString(1)); value.put("category", rs.getString(2)); value.put("sizeBytes", rs.getLong(3));
          value.put("sha256", rs.getString(4)); value.put("chunkCount", rs.getInt(5)); value.put("analyzedChunkCount", rs.getInt(6));
          value.put("status", rs.getString(7)); value.put("skipReason", rs.getString(8)); return value;
        }).list();
    return noStore(Map.of("runId", run.id(), "status", run.status(), "completedChunkIds", completed,
        "failedChunks", failed, "files", files));
  }

  @GetMapping("/runs/{runId}/summaries")
  ResponseEntity<Map<String, Object>> summaries(@RequestHeader("Authorization") String authorization,
      @PathVariable UUID taskId, @PathVariable UUID runId,
      @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "100") int limit) {
    tokens.requireAgent(authorization);
    requireRun(taskId, runId);
    if (offset < 0 || limit < 1 || limit > 200) throw new IllegalArgumentException("source summary page is invalid");
    var values = jdbc.sql("SELECT chunk_id,file_path,ordinal,summary::text FROM source_read_chunks WHERE run_id=:run AND status='ANALYZED' ORDER BY file_path,ordinal OFFSET :offset LIMIT :limit")
        .param("run", runId).param("offset", offset).param("limit", limit).query((rs, row) -> {
          var value = new LinkedHashMap<String, Object>(); value.put("chunkId", rs.getString(1)); value.put("filePath", rs.getString(2));
          value.put("ordinal", rs.getInt(3)); value.put("summary", parseJson(rs.getString(4))); return value;
        }).list();
    return noStore(Map.of("runId", runId, "offset", offset, "limit", limit, "items", values, "hasMore", values.size() == limit));
  }

  @PostMapping("/runs/{runId}/chunks/{chunkId}/complete")
  ResponseEntity<Void> completeChunk(@RequestHeader("Authorization") String authorization,
      @PathVariable UUID taskId, @PathVariable UUID runId, @PathVariable String chunkId,
      @RequestBody ChunkCompletion request) {
    tokens.requireAgent(authorization);
    requireRun(taskId, runId);
    String status = enumValue(request == null ? null : request.status(), Set.of("ANALYZED", "FAILED"), "status");
    String summary = request == null ? null : request.summary();
    String failure = request == null ? null : request.failureCode();
    if (status.equals("ANALYZED")) {
      summary = required(summary, "summary", MAX_SUMMARY_BYTES);
      if (!isJsonObject(summary)) throw new IllegalArgumentException("source summary must be a JSON object");
      failure = null;
    } else {
      failure = required(failure, "failureCode", 128);
      if (!failure.matches("[A-Z][A-Z0-9_]{2,127}")) throw new IllegalArgumentException("failureCode is invalid");
      summary = null;
    }
    int updated = jdbc.sql("UPDATE source_read_chunks SET status=:status,attempts=attempts+1,summary=CAST(:summary AS jsonb),failure_code=:failure,updated_at=:now WHERE run_id=:run AND chunk_id=:chunk")
        .param("status", status).param("summary", summary).param("failure", failure).param("now", OffsetDateTime.now())
        .param("run", runId).param("chunk", chunkId).update();
    if (updated != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "SOURCE_READ_CHUNK_NOT_FOUND");
    jdbc.sql("""
        UPDATE source_read_files f SET analyzed_chunk_count=(SELECT count(*) FROM source_read_chunks c WHERE c.file_id=f.id AND c.status='ANALYZED'),
          status=CASE WHEN EXISTS(SELECT 1 FROM source_read_chunks c WHERE c.file_id=f.id AND c.status='FAILED') THEN 'FAILED'
            WHEN f.chunk_count>0 AND (SELECT count(*) FROM source_read_chunks c WHERE c.file_id=f.id AND c.status='ANALYZED')=f.chunk_count THEN 'ANALYZED'
            ELSE f.status END, updated_at=:now WHERE f.run_id=:run
        """).param("run", runId).param("now", OffsetDateTime.now()).update();
    refreshRunCounts(runId);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/runs/{runId}/chunks/{chunkId}/retry")
  ResponseEntity<Void> retryChunk(@RequestHeader("Authorization") String authorization,
      @PathVariable UUID taskId, @PathVariable UUID runId, @PathVariable String chunkId) {
    tokens.requireAgent(authorization);
    requireRun(taskId, runId);
    int updated = jdbc.sql("UPDATE source_read_chunks SET status='READING',attempts=attempts+1,updated_at=:now WHERE run_id=:run AND chunk_id=:chunk")
        .param("run", runId).param("chunk", chunkId).param("now", OffsetDateTime.now()).update();
    if (updated != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "SOURCE_READ_CHUNK_NOT_FOUND");
    refreshRunCounts(runId);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/runs/{runId}/complete")
  ResponseEntity<Void> completeRun(@RequestHeader("Authorization") String authorization,
      @PathVariable UUID taskId, @PathVariable UUID runId, @RequestBody RunCompletion request) {
    tokens.requireAgent(authorization);
    requireRun(taskId, runId);
    String status = enumValue(request == null ? null : request.status(), Set.of("COMPLETE", "FAILED"), "status");
    if (status.equals("COMPLETE")) {
      boolean incomplete = jdbc.sql("SELECT EXISTS(SELECT 1 FROM source_read_chunks WHERE run_id=:run AND status<>'ANALYZED')")
          .param("run", runId).query(Boolean.class).single();
      if (incomplete) throw new ResponseStatusException(HttpStatus.CONFLICT, "SOURCE_READ_INCOMPLETE");
    }
    jdbc.sql("UPDATE source_read_runs SET status=:status,updated_at=:now WHERE id=:run")
        .param("status", status).param("now", OffsetDateTime.now()).param("run", runId).update();
    return ResponseEntity.noContent().build();
  }

  private RunRow requireRun(UUID taskId, UUID runId) {
    return jdbc.sql("SELECT r.id,r.revision_sha256,r.scope,r.status FROM source_read_runs r WHERE r.id=:run AND r.task_id=:task")
        .param("run", runId).param("task", taskId).query((rs, row) -> new RunRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4)))
        .optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "SOURCE_READ_RUN_NOT_FOUND"));
  }

  private void requireTask(UUID taskId) {
    jdbc.sql("SELECT id FROM tasks WHERE id=:id AND deleted_at IS NULL").param("id", taskId).query(UUID.class).optional()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND"));
  }

  private void refreshRunCounts(UUID runId) {
    jdbc.sql("""
        UPDATE source_read_runs r SET total_files=(SELECT count(*) FROM source_read_files f WHERE f.run_id=r.id),
          total_chunks=(SELECT count(*) FROM source_read_chunks c WHERE c.run_id=r.id),
          analyzed_chunks=(SELECT count(*) FROM source_read_chunks c WHERE c.run_id=r.id AND c.status='ANALYZED'),
          failed_chunks=(SELECT count(*) FROM source_read_chunks c WHERE c.run_id=r.id AND c.status='FAILED'),
          skipped_files=(SELECT count(*) FROM source_read_files f WHERE f.run_id=r.id AND f.status='SKIPPED'),
          status=CASE WHEN EXISTS(SELECT 1 FROM source_read_chunks c WHERE c.run_id=r.id AND c.status='FAILED') THEN 'FAILED'
            WHEN EXISTS(SELECT 1 FROM source_read_chunks c WHERE c.run_id=r.id AND c.status='READING') THEN 'READING'
            ELSE r.status END, updated_at=:now WHERE r.id=:run
        """).param("run", runId).param("now", OffsetDateTime.now()).update();
  }

  private static ResponseEntity<Map<String, Object>> noStore(Map<String, Object> body) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma", "no-cache").body(body);
  }

  private static String canonicalPath(String value) {
    String path = required(value, "path", 512);
    if (path.startsWith("/") || path.contains("\\") || path.indexOf('\0') >= 0
        || java.util.Arrays.asList(path.split("/", -1)).stream().anyMatch(part -> part.isBlank() || part.equals(".") || part.equals(".."))) {
      throw new IllegalArgumentException("source read path is invalid");
    }
    return path;
  }

  private static String enumValue(String value, Set<String> allowed, String field) {
    String normalized = required(value, field, 32).toUpperCase(java.util.Locale.ROOT);
    if (!allowed.contains(normalized)) throw new IllegalArgumentException(field + " is invalid");
    return normalized;
  }

  private static String required(String value, String field, int maxBytes) {
    if (value == null || value.isBlank() || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > maxBytes) {
      throw new IllegalArgumentException(field + " is invalid");
    }
    return value;
  }

  private static void requireSha(String value) {
    if (value == null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("sha256 is invalid");
  }

  private static boolean isJsonObject(String value) {
    try { var node = JSON.readTree(value); return node != null && node.isObject(); }
    catch (JacksonException invalid) { return false; }
  }

  private static Object parseJson(String value) {
    try { return JSON.readValue(value, Object.class); }
    catch (JacksonException invalid) { throw new IllegalStateException("stored source summary is invalid", invalid); }
  }

  record RunRequest(String revisionSha256, String scope, int totalEntries) { }
  record FileRequest(String path, String category, long sizeBytes, String sha256, int chunkCount,
                     String status, String skipReason) { }
  record ChunkBatch(List<ChunkRequest> chunks) { }
  record ChunkRequest(String chunkId, String filePath, int ordinal, long startOffset, long endOffset,
                      String chunkSha256) { }
  record ChunkCompletion(String status, String summary, String failureCode) { }
  record RunCompletion(String status) { }
  private record RunRow(UUID id, String revisionSha256, String scope, String status) { }
}
