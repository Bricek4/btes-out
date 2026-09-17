package studio.agent.worker;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import studio.agent.contracts.TaskStatus;
import studio.agent.contracts.TaskType;
import studio.agent.contracts.WorkerTaskRequest;

class WorkerExecutionServiceTest {
  private static final String MARKER = "<!-- agent-studio:screenshot:v1 {\"id\":\"users\",\"loginProfileRef\":\"admin\",\"target\":\"user list\",\"menuPath\":[\"Administration\",\"Users\"],\"caption\":\"Users\"} -->";

  @Test void generatesAndPublishesARealDocumentAndManifest() {
    UUID taskId = UUID.randomUUID();
    var platform = new RecordingPlatform(context(taskId, TaskType.PROJECT_DOCS,
        Map.of("title", "API", "outputPath", "docs/api.md"), null), "route /users");
    var service = new WorkerExecutionService(platform, new RecordingBrowser(),
        provider -> prompt -> "## Users\nGET /users");

    var result = service.execute(request(taskId, TaskType.PROJECT_DOCS));

    assertEquals(TaskStatus.SUCCEEDED, result.completion().status());
    assertEquals("artifact://docs/" + platform.primaryArtifactId, result.artifactReference());
    assertTrue(platform.publishedKinds.size() >= 12);
    assertTrue(platform.publishedKinds.contains(AgentPlatformClient.ArtifactKind.DIAGRAM));
    assertTrue(platform.publishedKinds.stream().filter(kind -> kind == AgentPlatformClient.ArtifactKind.DOC).count() >= 7);
    assertTrue(platform.uploads.stream().anyMatch(upload -> upload.name().equals("docs/api.md")));
    assertTrue(platform.uploads.stream().anyMatch(upload -> upload.name().equals("docs/03-system-architecture.md")));
    assertTrue(platform.uploads.stream().anyMatch(upload -> upload.name().equals("manifests/source-read-coverage.json")));
    assertTrue(platform.uploads.stream().filter(upload -> upload.kind() == AgentPlatformClient.ArtifactKind.DOC)
        .map(upload -> new String(upload.bytes(), StandardCharsets.UTF_8))
        .anyMatch(content -> content.contains("GET /users") || content.contains("source evidence")));
  }

  @Test void accepts_a_realistic_archive_with_more_than_five_hundred_entries() throws Exception {
    UUID taskId = UUID.randomUUID();
    var platform = new RecordingPlatform(context(taskId, TaskType.PROJECT_DOCS,
        Map.of("title", "Large project", "outputPath", "docs/README.md"), null),
        archiveWithEntries(501));
    var service = new WorkerExecutionService(platform, new RecordingBrowser(),
        provider -> prompt -> "## Generated\nThe source was inspected.");

    var result = service.execute(request(taskId, TaskType.PROJECT_DOCS));

    assertEquals(TaskStatus.SUCCEEDED, result.completion().status());
    assertEquals("artifact://docs/" + platform.primaryArtifactId, result.artifactReference());
  }

  @Test void generatesSanitizesAndPublishesStandaloneHtmlAndManifest() {
    UUID taskId = UUID.randomUUID();
    var platform = new RecordingPlatform(context(taskId, TaskType.HTML,
        Map.of("title", "Status", "outputPath", "site/status.html"), null), "route /status");
    var service = new WorkerExecutionService(platform, new RecordingBrowser(), provider -> prompt ->
        "<main><h1>Status</h1><script>credential='no'</script></main>");

    var result = service.execute(request(taskId, TaskType.HTML));

    assertEquals(TaskStatus.SUCCEEDED, result.completion().status());
    assertEquals(List.of(AgentPlatformClient.ArtifactKind.MANIFEST, AgentPlatformClient.ArtifactKind.MANIFEST,
        AgentPlatformClient.ArtifactKind.HTML),
        platform.publishedKinds);
    String html = new String(platform.uploads.get(2).bytes(), StandardCharsets.UTF_8);
    assertTrue(html.contains("<html"));
    assertTrue(html.contains("viewport"));
    assertFalse(html.contains("<script"));
  }

  @Test void resolvesAStandaloneScreenshotAndPublishesItsManifest() {
    UUID taskId = UUID.randomUUID();
    var parameters = Map.<String, Object>of("content", MARKER, "baseUrl", "https://fixture.test");
    var platform = new RecordingPlatform(context(taskId, TaskType.SCREENSHOT, parameters,
        "https://fixture.test"), "Administration Users route");
    var browser = new RecordingBrowser();
    var service = new WorkerExecutionService(platform, browser, provider -> prompt -> "unused");

    var result = service.execute(request(taskId, TaskType.SCREENSHOT));

    assertEquals(TaskStatus.SUCCEEDED, result.completion().status());
    assertEquals("artifact://" + taskId + "/users/users.png", result.artifactReference());
    assertEquals(List.of(AgentPlatformClient.ArtifactKind.MANIFEST, AgentPlatformClient.ArtifactKind.MANIFEST,
        AgentPlatformClient.ArtifactKind.MANIFEST), platform.publishedKinds);
    assertTrue(new String(platform.uploads.getLast().bytes(), StandardCharsets.UTF_8)
        .contains("artifact://" + taskId + "/users/users.png"));
    assertNull(result.approvalRequest());
  }

  @Test void preservesTypedApprovalWhenSourceEvidenceCannotProveTheMarkerPath() {
    UUID taskId = UUID.randomUUID();
    var parameters = Map.<String, Object>of("content", MARKER, "baseUrl", "https://fixture.test");
    var platform = new RecordingPlatform(context(taskId, TaskType.SCREENSHOT, parameters,
        "https://fixture.test"), "unrelated route");
    var service = new WorkerExecutionService(platform, new RecordingBrowser(), provider -> prompt -> "unused");

    var result = service.execute(request(taskId, TaskType.SCREENSHOT));

    assertNull(result.completion());
    assertEquals("SCREENSHOT_ROUTE_AMBIGUITY", result.approvalRequest().type());
    assertEquals("users", result.approvalRequest().markerId());
    assertTrue(result.approvalRequest().reference().matches(
        "approval://screenshot-route/users/[0-9a-f]{64}"));
    assertEquals(List.of(AgentPlatformClient.ArtifactKind.MANIFEST, AgentPlatformClient.ArtifactKind.MANIFEST), platform.publishedKinds);

    var approved = service.execute(request(taskId, TaskType.SCREENSHOT), result.approvalRequest().reference());
    assertEquals(TaskStatus.SUCCEEDED, approved.completion().status());
    assertEquals("artifact://" + taskId + "/users/users.png", approved.artifactReference());
    assertEquals(List.of(AgentPlatformClient.ArtifactKind.MANIFEST, AgentPlatformClient.ArtifactKind.MANIFEST,
        AgentPlatformClient.ArtifactKind.MANIFEST), platform.publishedKinds);
  }

  @Test void neverAppliesAnApprovalToRegeneratedContent() {
    UUID taskId = UUID.randomUUID();
    var parameters = Map.<String, Object>of("title", "Guide", "outputPath", "docs/guide.md",
        "baseUrl", "https://fixture.test");
    var platform = new RecordingPlatform(context(taskId, TaskType.USER_GUIDE, parameters,
        "https://fixture.test"), "unrelated route");
    var completions = new java.util.concurrent.atomic.AtomicInteger();
    var service = new WorkerExecutionService(platform, new RecordingBrowser(), provider -> prompt -> {
      if (prompt.startsWith("Analyze source chunk")) return "{\"facts\":[]}";
      return completions.getAndIncrement() == 0 ? "# First\n" + MARKER : "# Changed without markers";
    });

    var first = service.execute(request(taskId, TaskType.USER_GUIDE));
    assertNotNull(first.approvalRequest());
    var retried = service.execute(request(taskId, TaskType.USER_GUIDE), first.approvalRequest().reference());

    assertEquals(TaskStatus.FAILED, retried.completion().status());
    assertEquals("APPROVED_CANDIDATE_CHANGED", retried.completion().failureCode());
    assertEquals(List.of(AgentPlatformClient.ArtifactKind.MANIFEST, AgentPlatformClient.ArtifactKind.MANIFEST), platform.publishedKinds);
  }

  @Test void reusesACompletedExecutionWithoutRegeneratingOrRecapturing() {
    UUID taskId = UUID.randomUUID();
    var platform = new RecordingPlatform(context(taskId, TaskType.SCREENSHOT,
        Map.of("content", MARKER, "baseUrl", "https://fixture.test"), "https://fixture.test"),
        "Administration Users route");
    var browser = new RecordingBrowser();
    var service = new WorkerExecutionService(platform, browser, provider -> prompt -> "unused");
    WorkerTaskRequest request = request(taskId, TaskType.SCREENSHOT);

    var first = service.execute(request);
    var repeated = service.execute(request);

    assertSame(first, repeated);
    assertEquals(1, browser.screenshots);
    assertEquals(List.of(AgentPlatformClient.ArtifactKind.MANIFEST, AgentPlatformClient.ArtifactKind.MANIFEST,
        AgentPlatformClient.ArtifactKind.MANIFEST), platform.publishedKinds);
  }

  private static WorkerTaskRequest request(UUID taskId, TaskType type) {
    return new WorkerTaskRequest(taskId, UUID.randomUUID(), type, "source://revision",
        "template://version", "parameters://task", "provider://profile");
  }

  private static AgentTaskContext context(UUID taskId, TaskType type, Map<String, Object> parameters,
      String baseUrl) {
    String format = type == TaskType.HTML ? "html" : "markdown";
    return new AgentTaskContext(taskId, URI.create("https://objects.test/source"),
        new AgentTemplateContext(UUID.randomUUID(), format, "# {{title}}\n{{content}}",
            "<!doctype html><html><body>{{content}}</body></html>", "", "{}"),
        parameters, UUID.randomUUID(), "deepseek-chat", Set.of("admin"), baseUrl);
  }

  private static byte[] archiveWithEntries(int count) throws IOException {
    var bytes = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(bytes)) {
      for (int index = 0; index < count; index++) {
        zip.putNextEntry(new ZipEntry("src/File" + index + ".java"));
        zip.write(("class File" + index + " {}").getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    }
    return bytes.toByteArray();
  }

  private static final class RecordingPlatform implements AgentPlatformGateway {
    private final AgentTaskContext context;
    private final byte[] source;
    private final List<AgentPlatformClient.ArtifactKind> publishedKinds = new ArrayList<>();
    private final List<AgentPlatformClient.ArtifactUpload> uploads = new ArrayList<>();
    private final Map<String, AgentPlatformClient.PublishedArtifact> publishedByKey = new java.util.HashMap<>();
    private final UUID primaryArtifactId = UUID.randomUUID();
    private int published;
    RecordingPlatform(AgentTaskContext context, String source) {
      this(context, source.getBytes(StandardCharsets.UTF_8));
    }
    RecordingPlatform(AgentTaskContext context, byte[] source) {
      this.context = context;
      this.source = source.clone();
    }
    public AgentTaskContext context(UUID taskId) { return context; }
    public ProviderConnection provider(UUID taskId) {
      return new ProviderConnection("https://api.deepseek.com", "deepseek-chat", "test-key", Map.of());
    }
    public byte[] fetchSource(URI sourceUrl) { return source.clone(); }
    public AgentPlatformClient.PublishedArtifact publish(UUID taskId, AgentPlatformClient.ArtifactUpload upload) {
      String key = upload.kind() + "|" + upload.name() + "|" + java.util.Arrays.hashCode(upload.bytes())
          + "|" + upload.manifest();
      AgentPlatformClient.PublishedArtifact existing = publishedByKey.get(key);
      if (existing != null) return existing;
      publishedKinds.add(upload.kind());
      uploads.add(upload);
      UUID id = upload.kind() != AgentPlatformClient.ArtifactKind.MANIFEST && published++ == 0
          ? primaryArtifactId : UUID.randomUUID();
      String segment = switch (upload.kind()) { case DOC -> "docs"; case HTML -> "site"; case MANIFEST -> "manifests"; case DIAGRAM -> "diagrams"; };
      var result = new AgentPlatformClient.PublishedArtifact(id, UUID.randomUUID(),
          "artifact://" + segment + "/" + id, "sha");
      publishedByKey.put(key, result);
      return result;
    }
  }

  private static final class RecordingBrowser implements BrowserWorkerClient {
    private UUID taskId;
    private int screenshots;
    public String open(UUID taskId, String baseUrl, String loginProfileReference) { this.taskId = taskId; return "session"; }
    public void login(String sessionId) { }
    public BrowserSnapshot snapshot(String sessionId) { return new BrowserSnapshot("https://fixture.test/users", "heading user list"); }
    public void navigate(String sessionId, String target) { }
    public void click(String sessionId, SemanticLocator locator) { }
    public void clickMenuItem(String sessionId, String accessibleName) { }
    public void fill(String sessionId, SemanticLocator locator, String value) { }
    public void select(String sessionId, SemanticLocator locator, String value) { }
    public void check(String sessionId, SemanticLocator locator) { }
    public void uncheck(String sessionId, SemanticLocator locator) { }
    public void waitFor(String sessionId, SemanticLocator locator) { }
    public String screenshot(String sessionId, String markerId, String caption) {
      screenshots++;
      return "artifact://" + taskId + "/" + markerId + "/" + markerId + ".png";
    }
    public void close(String sessionId) { }
  }
}
