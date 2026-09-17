package studio.agent.worker;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Analyzes every planned project chunk and keeps a compact, evidence-linked summary. */
public final class SourceReadAnalyzer {
  private static final int MAX_SUMMARY_CHARS = 12_000;
  private static final int MAX_SUMMARY_BYTES = 12_000;
  private static final int MAX_CHUNK_ATTEMPTS = 3;
  private static final long RETRY_BACKOFF_MILLIS = 200L;
  private static final Set<String> ANALYSIS_FIELDS = Set.of(
      "facts", "symbols", "routes", "dataStores", "dependencies", "uiTargets", "risks", "unknowns", "evidence");
  private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
      "(?im)(\\b(?:api[_-]?key|client[_-]?secret|password|passwd|access[_-]?token|authorization)\\b\\s*[:=]\\s*)(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;]+)");
  private static final ObjectMapper JSON = new ObjectMapper();

  private final ArtifactGenerationService.ModelGateway model;
  private final SourceReadCheckpoint checkpoint;
  private final Consumer<SourceReadProgress> progress;
  private final int concurrency;
  private final SourceReadLimiter limiter;

  public SourceReadAnalyzer(ArtifactGenerationService.ModelGateway model, SourceReadCheckpoint checkpoint) {
    this(model, checkpoint, ignored -> { }, configuredConcurrency(), SourceReadLimiter.shared());
  }

  SourceReadAnalyzer(ArtifactGenerationService.ModelGateway model, SourceReadCheckpoint checkpoint,
      Consumer<SourceReadProgress> progress) {
    this(model, checkpoint, progress, configuredConcurrency(), SourceReadLimiter.shared());
  }

  SourceReadAnalyzer(ArtifactGenerationService.ModelGateway model, SourceReadCheckpoint checkpoint,
      Consumer<SourceReadProgress> progress, int concurrency) {
    this(model, checkpoint, progress, concurrency, SourceReadLimiter.shared());
  }

  SourceReadAnalyzer(ArtifactGenerationService.ModelGateway model, SourceReadCheckpoint checkpoint,
      Consumer<SourceReadProgress> progress, int concurrency, SourceReadLimiter limiter) {
    this.model = Objects.requireNonNull(model, "model is required");
    this.checkpoint = Objects.requireNonNull(checkpoint, "checkpoint is required");
    this.progress = Objects.requireNonNull(progress, "progress callback is required");
    this.limiter = Objects.requireNonNull(limiter, "source read limiter is required");
    if (concurrency < 1 || concurrency > SourceReadLimiter.DEFAULT_MAX_IN_FLIGHT) {
      throw new IllegalArgumentException("source read concurrency is invalid");
    }
    this.concurrency = concurrency;
  }

  public SourceReadResult analyze(SourceReadPlan plan) {
    Objects.requireNonNull(plan, "read plan is required");
    var analyses = new ArrayList<SourceReadCoverageEntry>();
    var summaries = new LinkedHashMap<String, String>();
    var facts = new LinkedHashMap<String, SourceFacts>();
    int analyzed = 0;
    int failed = 0;
    Map<String, String> existingSummaries = checkpoint.completed();
    ExecutorService executor = Executors.newFixedThreadPool(concurrency);
    try {
      // Extract facts first, then submit every pending chunk across every file. Waiting inside
      // the file loop would serialize repositories where most files contain only one chunk.
      var pending = new ArrayList<PendingChunk>();
      for (SourceReadFile file : plan.files()) {
        facts.put(file.path(), SourceFactExtractor.extract(file));
        if (file.status() == SourceReadStatus.SKIPPED) {
          analyses.add(new SourceReadCoverageEntry(file.path(), null, SourceReadStatus.SKIPPED, file.skipReason()));
          continue;
        }
        for (SourceReadChunk chunk : file.chunks()) {
          String existing = existingSummaries.get(chunk.chunkId());
          if (existing != null) {
            pending.add(new PendingChunk(chunk, null, existing, null));
          } else {
            Future<String> future = executor.submit(() -> analyzeChunk(chunk, plan, facts.get(file.path())));
            pending.add(new PendingChunk(chunk, future, null, null));
          }
        }
      }
      for (PendingChunk item : pending) {
        SourceReadChunk chunk = item.chunk();
        try {
          String summary = item.summary() == null ? item.future().get() : item.summary();
          if (item.summary() == null) checkpoint.complete(chunk, summary);
          summaries.put(chunk.chunkId(), summary);
          analyses.add(new SourceReadCoverageEntry(chunk.filePath(), chunk.chunkId(),
              SourceReadStatus.ANALYZED, null));
          analyzed++;
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new SourceReadException("SOURCE_READ_INTERRUPTED", interrupted);
        } catch (ExecutionException failure) {
          String code = failureCode(failure.getCause());
          checkpoint.fail(chunk, code);
          analyses.add(new SourceReadCoverageEntry(chunk.filePath(), chunk.chunkId(),
              SourceReadStatus.FAILED, code));
          failed++;
        } catch (RuntimeException failure) {
          String code = failureCode(failure);
          checkpoint.fail(chunk, code);
          analyses.add(new SourceReadCoverageEntry(chunk.filePath(), chunk.chunkId(),
              SourceReadStatus.FAILED, code));
          failed++;
        }
        progress.accept(new SourceReadProgress(analyzed + failed, plan.totalChunks(), chunk.filePath(), chunk.ordinal()));
      }
    } finally {
      executor.shutdownNow();
    }
    return new SourceReadResult(plan, List.copyOf(analyses), Map.copyOf(summaries), Map.copyOf(facts), analyzed, failed);
  }

  private static String failureCode(Throwable failure) {
    if (failure instanceof SourceReadException sourceFailure) return sourceFailure.code();
    return "SOURCE_CHUNK_ANALYSIS_FAILED";
  }

  private String analyzeChunk(SourceReadChunk chunk, SourceReadPlan plan, SourceFacts facts) {
    String content = redact(chunk.content());
    String prompt = "Analyze source chunk " + chunk.filePath() + " chunk " + chunk.ordinal()
        + " for a project-wide architecture, user guide, HTML, and screenshot workflow.\n"
        + "This is evidence only. Return one JSON object with only these keys: facts, symbols, routes, dataStores, dependencies, uiTargets, risks, unknowns, evidence.\n"
        + "Do not invent behavior. Evidence entries must reference this file and the supplied byte offsets.\n"
        + "File SHA-256: " + plan.file(chunk.filePath()).sha256() + "\n"
        + "Chunk SHA-256: " + chunk.sha256() + "\n"
        + "Chunk byte offsets: " + chunk.startOffset() + "-" + chunk.endOffset() + "\n"
        + "Chunk relationship: " + relationship(plan, chunk) + "\n"
        + "Static facts extracted without the model:\n" + json(facts) + "\n"
        + "Source chunk:\n" + content;
    for (int attempt = 1; attempt <= MAX_CHUNK_ATTEMPTS; attempt++) {
      try {
        String response = callModel(prompt);
        if (response == null || response.isBlank()) throw new SourceReadException("SOURCE_CHUNK_ANALYSIS_EMPTY");
        return normalizeResponse(response, chunk);
      } catch (RuntimeException failure) {
        if (!retryable(failure) || attempt == MAX_CHUNK_ATTEMPTS) throw failure;
        checkpoint.retry(chunk);
        backoff(attempt);
      }
    }
    throw new SourceReadException("SOURCE_CHUNK_ANALYSIS_FAILED");
  }

  private String callModel(String prompt) {
    try {
      limiter.acquire();
      try {
        return model.complete(prompt);
      } finally {
        limiter.release();
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new SourceReadException("SOURCE_READ_INTERRUPTED", interrupted);
    }
  }

  private static boolean retryable(Throwable failure) {
    if (failure instanceof SourceReadException sourceFailure) {
      String code = sourceFailure.code();
      return "SOURCE_CHUNK_ANALYSIS_EMPTY".equals(code)
          || "MODEL_REQUEST_FAILED".equals(code)
          || "PROVIDER_TIMEOUT".equals(code)
          || "PROVIDER_RATE_LIMITED".equals(code);
    }
    if (failure instanceof IllegalArgumentException) return false;
    // Provider SDKs do not expose one stable exception type across HTTP, timeout and
    // connection failures. At this point the only work being retried is model.complete;
    // structured-response validation happens after the call and is deliberately non-retryable.
    return failure instanceof RuntimeException;
  }

  private static void backoff(int attempt) {
    try {
      Thread.sleep(RETRY_BACKOFF_MILLIS * attempt);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new SourceReadException("SOURCE_READ_INTERRUPTED", interrupted);
    }
  }

  private static int configuredConcurrency() {
    String value = System.getenv("SOURCE_READ_PER_TASK_CONCURRENCY");
    if (value == null || value.isBlank()) return SourceReadLimiter.DEFAULT_MAX_IN_FLIGHT;
    try {
      int parsed = Integer.parseInt(value.trim());
      return parsed < 1 || parsed > SourceReadLimiter.DEFAULT_MAX_IN_FLIGHT
          ? SourceReadLimiter.DEFAULT_MAX_IN_FLIGHT : parsed;
    } catch (NumberFormatException ignored) {
      return SourceReadLimiter.DEFAULT_MAX_IN_FLIGHT;
    }
  }

  private static String relationship(SourceReadPlan plan, SourceReadChunk chunk) {
    SourceReadFile file = plan.file(chunk.filePath());
    String previous = chunk.ordinal() == 0 ? "none"
        : file.chunks().get(chunk.ordinal() - 1).chunkId();
    String next = chunk.ordinal() + 1 >= file.chunks().size() ? "none"
        : file.chunks().get(chunk.ordinal() + 1).chunkId();
    return "previous=" + previous + ", next=" + next;
  }

  private static String normalizeResponse(String response, SourceReadChunk chunk) {
    String value = response.strip();
    if (value.startsWith("```") && value.endsWith("```")) {
      int newline = value.indexOf('\n');
      value = newline >= 0 ? value.substring(newline + 1, value.length() - 3).strip() : value;
    }
    try {
      JsonNode root = JSON.readTree(value);
      if (root != null && root.isObject()) {
        var filtered = new LinkedHashMap<String, JsonNode>();
        root.properties().forEach(entry -> {
          if (ANALYSIS_FIELDS.contains(entry.getKey())) filtered.put(entry.getKey(), entry.getValue());
        });
        filtered.putIfAbsent("evidence", JSON.createArrayNode().addObject()
            .put("path", chunk.filePath()).put("startOffset", chunk.startOffset()).put("endOffset", chunk.endOffset()));
        return limit(JSON.writeValueAsString(filtered));
      }
    } catch (JacksonException ignored) {
      // Existing providers may return plain text despite the structured prompt. It is still
      // recorded as an analyzed fact so coverage remains honest and the final reducer can use it.
    }
    var fallback = new LinkedHashMap<String, Object>();
    fallback.put("facts", List.of(limit(value)));
    fallback.put("unknowns", List.of("MODEL_RETURNED_UNSTRUCTURED_ANALYSIS"));
    fallback.put("evidence", List.of(Map.of("path", chunk.filePath(), "startOffset", chunk.startOffset(),
        "endOffset", chunk.endOffset())));
    try { return JSON.writeValueAsString(fallback); }
    catch (JacksonException invalid) { throw new SourceReadException("SOURCE_CHUNK_ANALYSIS_INVALID", invalid); }
  }

  private static String redact(String value) {
    return SECRET_ASSIGNMENT.matcher(value == null ? "" : value).replaceAll("$1[REDACTED]");
  }

  private static String limit(String value) {
    if (value == null) return "";
    if (value.length() <= MAX_SUMMARY_CHARS
        && value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= MAX_SUMMARY_BYTES) return value;
    String suffix = "\n[summary truncated]";
    int budget = MAX_SUMMARY_BYTES - suffix.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    int high = Math.min(value.length(), MAX_SUMMARY_CHARS);
    int low = 0;
    while (low < high) {
      int middle = (low + high + 1) >>> 1;
      if (value.substring(0, middle).getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= budget) low = middle;
      else high = middle - 1;
    }
    if (low > 0 && low < value.length() && Character.isHighSurrogate(value.charAt(low - 1))) low--;
    return value.substring(0, low) + suffix;
  }

  private static String json(SourceFacts facts) {
    try { return JSON.writeValueAsString(facts == null ? Map.of() : facts); }
    catch (JacksonException invalid) { throw new SourceReadException("SOURCE_FACTS_INVALID", invalid); }
  }

  private record PendingChunk(SourceReadChunk chunk, Future<String> future, String summary, String ignored) { }
}

interface SourceReadCheckpoint {
  Map<String, String> completed();
  default void retry(SourceReadChunk chunk) { }
  void complete(SourceReadChunk chunk, String summary);
  void fail(SourceReadChunk chunk, String code);
  default void finish(SourceReadStatus status, String failureCode) { }

  static SourceReadCheckpoint inMemory() {
    return new InMemorySourceReadCheckpoint();
  }
}

final class InMemorySourceReadCheckpoint implements SourceReadCheckpoint {
  private final Map<String, String> summaries = new ConcurrentHashMap<>();
  private final Map<String, String> failures = new ConcurrentHashMap<>();
  private final Map<String, Integer> attemptCounts = new ConcurrentHashMap<>();

  @Override public Map<String, String> completed() { return Map.copyOf(summaries); }
  @Override public void retry(SourceReadChunk chunk) { attemptCounts.merge(chunk.chunkId(), 1, Integer::sum); }
  @Override public void complete(SourceReadChunk chunk, String summary) {
    summaries.put(chunk.chunkId(), summary); failures.remove(chunk.chunkId());
    attemptCounts.merge(chunk.chunkId(), 1, Integer::sum);
  }
  @Override public void fail(SourceReadChunk chunk, String code) {
    failures.put(chunk.chunkId(), code); attemptCounts.merge(chunk.chunkId(), 1, Integer::sum);
  }
  int attempts(String chunkId) { return attemptCounts.getOrDefault(chunkId, 0); }
}

record SourceReadCoverageEntry(String filePath, String chunkId, SourceReadStatus status, String reason) { }

record SourceReadProgress(int completedOrFailedChunks, int totalChunks, String filePath, int chunkOrdinal) { }

record SourceReadResult(SourceReadPlan plan, List<SourceReadCoverageEntry> coverage,
                        Map<String, String> summaries, Map<String, SourceFacts> facts,
                        int analyzedChunks, int failedChunks) {
  SourceReadResult {
    coverage = List.copyOf(coverage == null ? List.of() : coverage);
    summaries = Map.copyOf(summaries == null ? Map.of() : summaries);
    facts = Map.copyOf(facts == null ? Map.of() : facts);
  }

  boolean complete() { return failedChunks == 0 && analyzedChunks == plan.totalChunks(); }

  String synthesisContext() {
    var out = new StringBuilder();
    for (SourceReadFile file : plan.files()) {
      SourceFacts fileFacts = facts.get(file.path());
      if (fileFacts != null) out.append("\n--- static facts: ").append(file.path()).append(" ---\n").append(fileFacts);
      for (SourceReadChunk chunk : file.chunks()) {
        String summary = summaries.get(chunk.chunkId());
        if (summary != null) out.append("\n--- ").append(chunk.filePath()).append(" #").append(chunk.ordinal()).append(" ---\n").append(summary);
      }
    }
    return out.toString();
  }

  String routeEvidence() {
    var out = new StringBuilder();
    facts.values().forEach(value -> out.append('\n').append(value));
    for (SourceReadCoverageEntry entry : coverage) {
      if (entry.status() == SourceReadStatus.ANALYZED) out.append('\n').append(entry.filePath()).append('\n');
    }
    return out.append(synthesisContext()).toString();
  }

  String sourceText() {
    var out = new StringBuilder();
    for (SourceReadFile file : plan.files()) {
      for (SourceReadChunk chunk : file.chunks()) {
        out.append("\n--- ").append(chunk.filePath()).append(" #").append(chunk.ordinal()).append(" ---\n")
            .append(chunk.content());
      }
    }
    return out.toString();
  }

  String coverageJson(java.util.UUID taskId) {
    var fileRows = new ArrayList<Map<String, Object>>();
    for (SourceReadFile file : plan.files()) {
      var row = new LinkedHashMap<String, Object>();
      row.put("path", file.path()); row.put("category", file.category().name()); row.put("sizeBytes", file.sizeBytes());
      row.put("sha256", file.sha256()); row.put("chunkCount", file.chunks().size());
      String status = file.status().name();
      if (file.status() != SourceReadStatus.SKIPPED) {
        boolean fileFailed = coverage.stream().anyMatch(entry -> file.path().equals(entry.filePath())
            && entry.status() == SourceReadStatus.FAILED);
        boolean fileComplete = file.chunks().stream().allMatch(chunk -> summaries.containsKey(chunk.chunkId()));
        status = fileFailed ? SourceReadStatus.FAILED.name() : fileComplete ? SourceReadStatus.ANALYZED.name() : status;
      }
      row.put("status", status);
      if (file.skipReason() != null) row.put("skipReason", file.skipReason());
      fileRows.add(row);
    }
    var chunkRows = coverage.stream().filter(entry -> entry.chunkId() != null).map(entry -> {
      var row = new LinkedHashMap<String, Object>(); row.put("filePath", entry.filePath()); row.put("chunkId", entry.chunkId());
      row.put("status", entry.status().name()); if (entry.reason() != null) row.put("reason", entry.reason()); return row;
    }).toList();
    var root = new LinkedHashMap<String, Object>();
    root.put("taskId", taskId); root.put("archiveSha256", plan.archiveSha256()); root.put("totalEntries", plan.totalEntries());
    root.put("expandedBytes", plan.expandedBytes()); root.put("totalFiles", plan.files().size()); root.put("totalChunks", plan.totalChunks());
    root.put("analyzedChunks", analyzedChunks); root.put("failedChunks", failedChunks); root.put("complete", complete());
    root.put("files", fileRows); root.put("chunks", chunkRows);
    try { return new ObjectMapper().writeValueAsString(root); }
    catch (JacksonException invalid) { throw new SourceReadException("SOURCE_COVERAGE_INVALID", invalid); }
  }
}
