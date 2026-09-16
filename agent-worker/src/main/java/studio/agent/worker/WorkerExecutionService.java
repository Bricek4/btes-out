package studio.agent.worker;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import studio.agent.contracts.TaskStatus;
import studio.agent.contracts.TaskType;
import studio.agent.contracts.WorkerTaskRequest;
import studio.agent.contracts.WorkerTaskResult;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Executes a task from short-lived Platform context and returns only registered artifact references. */
public final class WorkerExecutionService implements WorkerTaskExecutor {
  private static final Logger LOG = LoggerFactory.getLogger(WorkerExecutionService.class);
  private static final int MAX_ARCHIVE_ENTRIES = 500;
  private static final int MAX_ENTRY_BYTES = 1_000_000;
  private static final int MAX_EVIDENCE_CHARS = 5_000_000;
  private static final Set<String> TEXT_EXTENSIONS = Set.of(
      ".java", ".kt", ".kts", ".js", ".jsx", ".ts", ".tsx", ".vue", ".html", ".css",
      ".md", ".json", ".yaml", ".yml", ".properties", ".xml", ".sql", ".txt");
  private static final Pattern SCREENSHOT_REFERENCE = Pattern.compile(
      "artifact://[0-9a-fA-F-]+/[A-Za-z0-9_-]+/[A-Za-z0-9][A-Za-z0-9_.-]*\\.png");
  private static final ObjectMapper JSON = new ObjectMapper();

  private final AgentPlatformGateway platform;
  private final BrowserWorkerClient browser;
  private final Function<ProviderConnection, ArtifactGenerationService.ModelGateway> models;
  private final java.util.concurrent.ConcurrentMap<ExecutionKey, LocalWorkerResult> completedExecutions =
      new java.util.concurrent.ConcurrentHashMap<>();

  public WorkerExecutionService(AgentPlatformGateway platform, BrowserWorkerClient browser,
      Function<ProviderConnection, ArtifactGenerationService.ModelGateway> models) {
    this.platform = Objects.requireNonNull(platform, "platform is required");
    this.browser = Objects.requireNonNull(browser, "browser is required");
    this.models = Objects.requireNonNull(models, "model factory is required");
  }

  public LocalWorkerResult execute(WorkerTaskRequest request) {
    return execute(request, null);
  }

  @Override public LocalWorkerResult execute(WorkerTaskRequest request, String approvedReference) {
    Objects.requireNonNull(request, "request is required");
    var key = new ExecutionKey(request, approvedReference);
    LocalWorkerResult result = completedExecutions.computeIfAbsent(key,
        ignored -> executeOnce(request, approvedReference));
    if (result.completion() != null && result.completion().status() == TaskStatus.FAILED) {
      completedExecutions.remove(key, result);
    }
    return result;
  }

  private LocalWorkerResult executeOnce(WorkerTaskRequest request, String approvedReference) {
    try {
      AgentTaskContext context = platform.context(request.taskId());
      if (!request.taskId().equals(context.taskId())) throw new PipelineFailure("AGENT_CONTEXT_TASK_MISMATCH");
      String evidence = sourceEvidence(platform.fetchSource(context.sourceUrl()));
      return request.type() == TaskType.SCREENSHOT
          ? executeScreenshot(request, context, evidence, approvedReference)
          : executeGenerated(request, context, evidence, approvedReference);
    } catch (PipelineFailure failure) {
      return failed(request.taskId(), failure.code);
    } catch (AgentPlatformClient.PlatformOperationException failure) {
      return failed(request.taskId(), safeCode(failure.getMessage(), "PLATFORM_OPERATION_FAILED"));
    } catch (SpringAiModelGateway.ModelCallFailure failure) {
      return failed(request.taskId(), safeCode(failure.getMessage(), "MODEL_REQUEST_FAILED"));
    } catch (IllegalArgumentException failure) {
      LOG.warn("agent execution validation failed: type={}, category={}",
          failure.getClass().getSimpleName(), validationCategory(failure));
      return failed(request.taskId(), "AGENT_TASK_INVALID");
    } catch (RuntimeException failure) {
      LOG.warn("agent execution failed: type={}", failure.getClass().getSimpleName());
      return failed(request.taskId(), "AGENT_EXECUTION_FAILED");
    }
  }

  private LocalWorkerResult executeGenerated(WorkerTaskRequest request, AgentTaskContext context,
      String evidence, String approvedReference) {
    AgentTemplateContext template = context.template();
    Map<String, Object> parameters = context.parameters();
    String outputPath = text(parameters, "outputPath", switch (request.type()) {
      case PROJECT_DOCS -> "docs/README.md";
      case USER_GUIDE -> "docs/user-guide.md";
      case HTML -> "site/index.html";
      case SCREENSHOT -> throw new IllegalStateException("handled separately");
    });
    String outputFormat = request.type() == TaskType.HTML ? "html" : "markdown";
    String templateBody = request.type() == TaskType.HTML ? template.html() : template.markdown();
    if (templateBody == null || templateBody.isBlank()) throw new PipelineFailure("TEMPLATE_BODY_UNAVAILABLE");
    ProviderConnection provider = platform.provider(request.taskId());
    if (!context.modelId().equals(provider.model())) throw new PipelineFailure("PROVIDER_MODEL_MISMATCH");
    var generation = new ArtifactGenerationService(models.apply(provider));
    var generated = generation.generate(new ArtifactGenerationService.GenerationRequest(request.type(), evidence,
        outputPath, outputFormat, templateBody, text(parameters, "title", "Generated project artifact"),
        parameters, text(parameters, "sourceChangeSummary", ""), text(parameters, "existingContent", ""),
        context.loginProfileReferences(), stringSet(parameters.get("availableAssets")),
        Boolean.TRUE.equals(parameters.get("incremental"))));
    ResolutionOutcome approvalCheck = validateApprovedCandidate(generated.content(), approvedReference);
    if (approvalCheck != null) return failed(request.taskId(), approvalCheck.failureCode());
    publishManifest(request, manifest(request, generated.path(), generated.content()));
    ResolutionOutcome resolved = resolveMarkers(request.taskId(), context, evidence, generated.content(), approvedReference);
    if (resolved.approval() != null) {
      return new LocalWorkerResult(null, null, resolved.approval());
    }
    if (resolved.failureCode() != null) return failed(request.taskId(), resolved.failureCode());
    byte[] content = resolved.content().getBytes(StandardCharsets.UTF_8);
    AgentPlatformClient.ArtifactKind kind = request.type() == TaskType.HTML
        ? AgentPlatformClient.ArtifactKind.HTML : AgentPlatformClient.ArtifactKind.DOC;
    String mediaType = request.type() == TaskType.HTML ? "text/html" : "text/markdown";
    String manifest = manifest(request, generated.path(), resolved.content());
    publishManifest(request, manifest);
    AgentPlatformClient.PublishedArtifact primary = platform.publish(request.taskId(),
        new AgentPlatformClient.ArtifactUpload(generated.path(), kind, mediaType, content, manifest));
    return succeeded(request.taskId(), primary.reference());
  }

  private LocalWorkerResult executeScreenshot(WorkerTaskRequest request, AgentTaskContext context,
      String evidence, String approvedReference) {
    String content = text(context.parameters(), "content", evidence);
    if (ScreenshotMarkers.parseAll(content).isEmpty()) throw new PipelineFailure("SCREENSHOT_MARKER_REQUIRED");
    ResolutionOutcome approvalCheck = validateApprovedCandidate(content, approvedReference);
    if (approvalCheck != null) return failed(request.taskId(), approvalCheck.failureCode());
    String manifest = manifest(request, "manifests/" + request.taskId() + ".json", content);
    // Publish the plan before browsing so an approval request always has an auditable intent.
    publishManifest(request, manifest);
    ResolutionOutcome resolved = resolveMarkers(request.taskId(), context, evidence, content, approvedReference);
    if (resolved.approval() != null) return new LocalWorkerResult(null, null, resolved.approval());
    if (resolved.failureCode() != null) return failed(request.taskId(), resolved.failureCode());
    List<String> references = screenshotReferences(resolved.content());
    if (references.isEmpty()) throw new PipelineFailure("SCREENSHOT_REFERENCE_MISSING");
    // The plan still contains unresolved markers. Publish a second immutable manifest after
    // replacement and return it for multi-screenshot jobs, so the result always describes the
    // artifacts that were actually captured.
    AgentPlatformClient.PublishedArtifact finalManifest = publishManifest(request,
        manifest(request, "manifests/" + request.taskId() + ".json", resolved.content()));
    String resultReference = references.size() == 1 ? references.getFirst() : finalManifest.reference();
    return succeeded(request.taskId(), resultReference);
  }

  private ResolutionOutcome resolveMarkers(UUID taskId, AgentTaskContext context, String evidence,
      String content, String approvedReference) {
    ResolutionOutcome approvalCheck = validateApprovedCandidate(content, approvedReference);
    if (approvalCheck != null) return approvalCheck;
    List<ScreenshotMarker> markers = ScreenshotMarkers.parseAll(content);
    if (markers.isEmpty()) return new ResolutionOutcome(content, null, null);
    String baseUrl = context.baseUrl();
    if (baseUrl == null || baseUrl.isBlank()) throw new PipelineFailure("SCREENSHOT_BASE_URL_UNAVAILABLE");
    ScreenshotResolution resolution = new ScreenshotResolver(browser).resolve(taskId, baseUrl, content,
        supportedEvidence(evidence, markers), context.loginProfileReferences());
    if (resolution.approvalRequest() != null) {
      String boundReference = bindApproval(resolution.approvalRequest().reference(), content);
      if (approvedReference != null && !boundReference.equals(approvedReference)) {
        return new ResolutionOutcome(content, "APPROVED_CANDIDATE_CHANGED", null);
      }
      if (boundReference.equals(approvedReference)) {
        resolution = new ScreenshotResolver(browser).resolve(taskId, baseUrl, content,
            declaredEvidence(markers), context.loginProfileReferences());
      } else {
        ScreenshotApprovalRequest approval = resolution.approvalRequest();
        return new ResolutionOutcome(content, null, new ApprovalBridge(approval.type(), approval.markerId(),
            approval.reasonCode(), boundReference));
      }
    }
    if (resolution.approvalRequest() != null) {
      ScreenshotApprovalRequest approval = resolution.approvalRequest();
      return new ResolutionOutcome(content, null, new ApprovalBridge(approval.type(), approval.markerId(),
          approval.reasonCode(), approval.reference()));
    }
    if (!resolution.completed()) {
      return new ResolutionOutcome(resolution.content(),
          safeCode(resolution.errorCode(), "SCREENSHOT_RESOLUTION_FAILED"), null);
    }
    if (resolution.content().contains("agent-studio:screenshot")) {
      throw new PipelineFailure("SCREENSHOT_MARKER_UNRESOLVED");
    }
    return new ResolutionOutcome(resolution.content(), null, null);
  }

  private static ResolutionOutcome validateApprovedCandidate(String content, String approvedReference) {
    if (approvedReference == null) return null;
    int slash = approvedReference.lastIndexOf('/');
    if (slash < 0 || slash == approvedReference.length() - 1) {
      return new ResolutionOutcome(content, "APPROVED_REFERENCE_INVALID", null);
    }
    String approvedHash = approvedReference.substring(slash + 1);
    if (!approvedHash.matches("[0-9a-f]{64}")) {
      return new ResolutionOutcome(content, "APPROVED_REFERENCE_INVALID", null);
    }
    return approvedHash.equals(sha256(content.getBytes(StandardCharsets.UTF_8)))
        ? null : new ResolutionOutcome(content, "APPROVED_CANDIDATE_CHANGED", null);
  }

  private AgentPlatformClient.PublishedArtifact publishManifest(WorkerTaskRequest request, String manifest) {
    return platform.publish(request.taskId(), new AgentPlatformClient.ArtifactUpload(
        "manifests/" + request.taskId() + ".json", AgentPlatformClient.ArtifactKind.MANIFEST,
        "application/json", manifest.getBytes(StandardCharsets.UTF_8), "{}"));
  }

  private static String manifest(WorkerTaskRequest request, String outputPath, String content) {
    try {
      var markerIds = ScreenshotMarkers.parseAll(content).stream().map(ScreenshotMarker::id).toList();
      var plannedReferences = markerIds.stream()
          .map(id -> "artifact://" + request.taskId() + "/" + id + "/" + id + ".png").toList();
      return JSON.writeValueAsString(Map.of("taskId", request.taskId().toString(),
          "type", request.type().name(), "outputPath", outputPath,
          "screenshotReferences", screenshotReferences(content),
          "plannedScreenshotReferences", plannedReferences, "remainingMarkerIds", markerIds));
    } catch (JacksonException invalid) {
      throw new PipelineFailure("ARTIFACT_MANIFEST_INVALID");
    }
  }

  private static List<String> screenshotReferences(String content) {
    var matches = new LinkedHashSet<String>();
    var matcher = SCREENSHOT_REFERENCE.matcher(content);
    while (matcher.find()) matches.add(matcher.group());
    return List.copyOf(matches);
  }

  private static List<String> supportedEvidence(String source, List<ScreenshotMarker> markers) {
    String lower = source.toLowerCase(Locale.ROOT);
    var result = new LinkedHashSet<String>();
    for (ScreenshotMarker marker : markers) {
      for (String candidate : marker.menuPath()) if (lower.contains(candidate.toLowerCase(Locale.ROOT))) result.add(candidate);
      for (ScreenshotAction action : marker.actions()) {
        String name = action.locator().name();
        if (lower.contains(name.toLowerCase(Locale.ROOT))) result.add(name);
      }
      if (marker.routePath() != null && source.contains(marker.routePath())) result.add(marker.routePath());
    }
    return List.copyOf(result);
  }

  private static List<String> declaredEvidence(List<ScreenshotMarker> markers) {
    var result = new LinkedHashSet<String>();
    for (ScreenshotMarker marker : markers) {
      result.addAll(marker.menuPath());
      for (ScreenshotAction action : marker.actions()) result.add(action.locator().name());
      if (marker.routePath() != null) result.add(marker.routePath());
    }
    return List.copyOf(result);
  }

  private static String bindApproval(String reference, String content) {
    return reference + "/" + sha256(content.getBytes(StandardCharsets.UTF_8));
  }

  private static String sha256(byte[] value) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
    catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
  }

  private static String sourceEvidence(byte[] source) {
    if (source.length >= 4 && source[0] == 'P' && source[1] == 'K' && source[2] == 3 && source[3] == 4) {
      return archiveEvidence(source);
    }
    return decodeUtf8(source, "SOURCE_ENCODING_UNSUPPORTED");
  }

  private static String archiveEvidence(byte[] archive) {
    var output = new StringBuilder();
    int entries = 0;
    try (var zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
      for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
        if (++entries > MAX_ARCHIVE_ENTRIES) throw new PipelineFailure("SOURCE_ARCHIVE_TOO_MANY_ENTRIES");
        String name = entry.getName();
        if (entry.isDirectory() || unsafeArchivePath(name) || !textFile(name)) continue;
        byte[] bytes = zip.readNBytes(MAX_ENTRY_BYTES + 1);
        if (bytes.length > MAX_ENTRY_BYTES) throw new PipelineFailure("SOURCE_FILE_TOO_LARGE");
        String text = decodeUtf8(bytes, "SOURCE_FILE_ENCODING_UNSUPPORTED");
        if (output.length() + name.length() + text.length() + 8 > MAX_EVIDENCE_CHARS) {
          throw new PipelineFailure("SOURCE_EVIDENCE_TOO_LARGE");
        }
        output.append("\n--- ").append(name).append(" ---\n").append(text);
      }
    } catch (IOException invalid) {
      throw new PipelineFailure("SOURCE_ARCHIVE_INVALID");
    }
    if (output.isEmpty()) throw new PipelineFailure("SOURCE_EVIDENCE_EMPTY");
    return output.toString();
  }

  private static boolean unsafeArchivePath(String name) {
    if (name == null || name.isBlank() || name.startsWith("/") || name.contains("\\")) return true;
    for (String part : name.split("/")) if (part.equals("..") || part.equals(".")) return true;
    return false;
  }

  private static boolean textFile(String name) {
    String lower = name.toLowerCase(Locale.ROOT);
    return TEXT_EXTENSIONS.stream().anyMatch(lower::endsWith);
  }

  private static String decodeUtf8(byte[] bytes, String errorCode) {
    try {
      return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    } catch (CharacterCodingException invalid) {
      throw new PipelineFailure(errorCode);
    }
  }

  private static String text(Map<String, Object> parameters, String name, String fallback) {
    Object value = parameters.get(name);
    if (value == null) return fallback;
    if (!(value instanceof String text) || text.isBlank() || text.length() > 2_000_000) {
      throw new PipelineFailure("TASK_PARAMETER_INVALID");
    }
    return text;
  }

  private static Set<String> stringSet(Object value) {
    if (value == null) return Set.of();
    if (!(value instanceof List<?> values) || values.size() > 1_000) throw new PipelineFailure("TASK_PARAMETER_INVALID");
    var result = new LinkedHashSet<String>();
    for (Object item : values) {
      if (!(item instanceof String text) || text.isBlank() || text.length() > 500) throw new PipelineFailure("TASK_PARAMETER_INVALID");
      result.add(text);
    }
    return Set.copyOf(result);
  }

  private static String safeCode(String value, String fallback) {
    return value != null && value.matches("[A-Z][A-Z0-9_]{2,80}") ? value : fallback;
  }

  private static String validationCategory(IllegalArgumentException failure) {
    String message = failure.getMessage();
    if (message == null) return "UNSPECIFIED";
    String lower = message.toLowerCase(Locale.ROOT);
    if (lower.contains("model") || lower.contains("provider")) return "MODEL_INPUT";
    if (lower.contains("source") || lower.contains("archive")) return "SOURCE_INPUT";
    if (lower.contains("template") || lower.contains("render")) return "TEMPLATE_INPUT";
    if (lower.contains("marker") || lower.contains("screenshot")) return "MARKER_INPUT";
    return "REQUEST_INPUT";
  }

  private static LocalWorkerResult succeeded(UUID taskId, String reference) {
    var completion = new WorkerTaskResult(taskId, TaskStatus.SUCCEEDED, reference, null);
    return new LocalWorkerResult(completion, reference, null);
  }

  private static LocalWorkerResult failed(UUID taskId, String code) {
    return new LocalWorkerResult(new WorkerTaskResult(taskId, TaskStatus.FAILED, null,
        safeCode(code, "AGENT_EXECUTION_FAILED")), null, null);
  }

  public record LocalWorkerResult(WorkerTaskResult completion, String artifactReference,
                                  ApprovalBridge approvalRequest) { }
  public record ApprovalBridge(String type, String markerId, String reasonCode, String reference) { }
  private record ExecutionKey(WorkerTaskRequest request, String approvedReference) { }
  private record ResolutionOutcome(String content, String failureCode, ApprovalBridge approval) { }
  private static final class PipelineFailure extends RuntimeException {
    private final String code;
    PipelineFailure(String code) { super(code); this.code = code; }
  }
}

@FunctionalInterface
interface WorkerTaskExecutor {
  WorkerExecutionService.LocalWorkerResult execute(WorkerTaskRequest request, String approvedReference);
}
