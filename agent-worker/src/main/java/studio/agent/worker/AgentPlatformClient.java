package studio.agent.worker;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Agent-scoped Platform API client. Presigned object URLs never receive worker authorization. */
public final class AgentPlatformClient implements AgentPlatformGateway {
  private static final int MAX_SOURCE_BYTES = 20_000_000;
  private static final int MAX_ARTIFACT_BYTES = 20_000_000;
  private static final int MAX_MANIFEST_BYTES = 1_000_000;
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> STRING_OBJECT_MAP = new TypeReference<>() { };

  private final RestClient platform;
  private final String token;
  private final java.util.concurrent.ConcurrentMap<PublicationKey, PublishedArtifact> completedPublications =
      new java.util.concurrent.ConcurrentHashMap<>();

  public AgentPlatformClient(String platformBaseUrl, String agentWorkerToken) {
    this.platform = RestClient.builder().baseUrl(requireHttpUri(platformBaseUrl).toString()).build();
    this.token = requireText(agentWorkerToken, "agent worker token is required");
  }

  public ProviderConnection provider(UUID taskId) {
    Objects.requireNonNull(taskId, "task id is required");
    Map<?, ?> response;
    try {
      response = platform.post().uri("/internal/tasks/{taskId}/provider-credential", taskId)
          .header(HttpHeaders.AUTHORIZATION, bearer()).retrieve().body(Map.class);
    } catch (RuntimeException failure) {
      throw new PlatformOperationException("PROVIDER_CREDENTIAL_UNAVAILABLE");
    }
    if (response == null) throw new PlatformOperationException("PROVIDER_CREDENTIAL_UNAVAILABLE");
    return new ProviderConnection(requireText(value(response, "endpoint"), "provider endpoint is unavailable"),
        requireText(value(response, "model"), "provider model is unavailable"),
        requireText(value(response, "apiKey"), "provider credential is unavailable"), options(response.get("options")));
  }

  @Override public AgentTaskContext context(UUID taskId) {
    Objects.requireNonNull(taskId, "task id is required");
    Map<?, ?> response;
    try {
      response = platform.get().uri("/internal/worker-context/agent/{taskId}", taskId)
          .header(HttpHeaders.AUTHORIZATION, bearer()).retrieve().body(Map.class);
    } catch (RuntimeException failure) {
      throw new PlatformOperationException("AGENT_CONTEXT_UNAVAILABLE");
    }
    if (response == null || !taskId.equals(uuid(response, "taskId"))) {
      throw new PlatformOperationException("AGENT_CONTEXT_INVALID");
    }
    Object rawTemplate = response.get("template");
    if (!(rawTemplate instanceof Map<?, ?> template)) throw new PlatformOperationException("AGENT_CONTEXT_INVALID");
    AgentTemplateContext parsedTemplate = new AgentTemplateContext(uuid(template, "id"),
        requireText(value(template, "format"), "template format is unavailable"),
        value(template, "markdown"), value(template, "html"), value(template, "css"),
        requireText(value(template, "schema"), "template schema is unavailable"));
    Map<String, Object> parameters = objectMap(response.get("parameters"), "TASK_PARAMETERS_INVALID");
    var profiles = new java.util.LinkedHashSet<String>();
    Object rawProfiles = response.get("loginProfiles");
    if (rawProfiles instanceof java.util.List<?> rows) {
      for (Object row : rows) {
        if (!(row instanceof Map<?, ?> profile)) throw new PlatformOperationException("AGENT_CONTEXT_INVALID");
        profiles.add(requireText(value(profile, "reference"), "login profile reference is unavailable"));
      }
    } else if (rawProfiles != null) {
      throw new PlatformOperationException("AGENT_CONTEXT_INVALID");
    }
    String baseUrl = value(response, "baseUrl");
    if ((baseUrl == null || baseUrl.isBlank()) && parameters.get("baseUrl") instanceof String configured) {
      baseUrl = configured;
    }
    return new AgentTaskContext(taskId, requireHttpUri(value(response, "sourceUrl")), parsedTemplate,
        parameters, uuid(response, "providerProfileId"),
        requireText(value(response, "modelId"), "model id is unavailable"), Set.copyOf(profiles), baseUrl);
  }

  @Override public byte[] fetchSource(URI presignedGetUrl) {
    URI source = requireHttpUri(presignedGetUrl == null ? null : presignedGetUrl.toString());
    try {
      byte[] bytes = RestClient.create().get().uri(source).retrieve().body(byte[].class);
      if (bytes == null || bytes.length == 0) throw new PlatformOperationException("SOURCE_EMPTY");
      if (bytes.length > MAX_SOURCE_BYTES) throw new PlatformOperationException("SOURCE_TOO_LARGE");
      return bytes;
    } catch (PlatformOperationException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw new PlatformOperationException("SOURCE_FETCH_FAILED");
    }
  }

  @Override public PublishedArtifact publish(UUID taskId, ArtifactUpload upload) {
    Objects.requireNonNull(taskId, "task id is required");
    Objects.requireNonNull(upload, "artifact upload is required");
    byte[] bytes = upload.bytes();
    String sha256 = sha256(bytes);
    String manifestSha256 = sha256(upload.manifest().getBytes(StandardCharsets.UTF_8));
    var key = new PublicationKey(taskId, upload.name(), upload.kind(), upload.mediaType(), sha256,
        manifestSha256);
    return completedPublications.computeIfAbsent(key, ignored -> publishOnce(taskId, upload, bytes,
        sha256, idempotencyKey(key)));
  }

  private PublishedArtifact publishOnce(UUID taskId, ArtifactUpload upload, byte[] bytes, String sha256,
      String idempotencyKey) {
    Map<?, ?> reservation;
    try {
      reservation = platform.post().uri("/internal/tasks/{taskId}/artifacts/presign", taskId)
          .header(HttpHeaders.AUTHORIZATION, bearer())
          .body(Map.of("name", upload.name(), "kind", upload.kind().name(), "mediaType", upload.mediaType(),
              "sizeBytes", bytes.length, "sha256", sha256, "idempotencyKey", idempotencyKey))
          .retrieve().body(Map.class);
    } catch (RuntimeException failure) {
      throw new PlatformOperationException("ARTIFACT_RESERVATION_FAILED");
    }
    if (reservation == null) throw new PlatformOperationException("ARTIFACT_RESERVATION_FAILED");
    if (!idempotencyKey.equals(value(reservation, "idempotencyKey"))) {
      throw new PlatformOperationException("ARTIFACT_IDEMPOTENCY_MISMATCH");
    }
    UUID artifactId = uuid(reservation, "artifactId");
    UUID reservationId = uuid(reservation, "reservationId");
    URI putUrl = requireHttpUri(value(reservation, "putUrl"));
    try {
      String checksumSha256 = java.util.Base64.getEncoder().encodeToString(HexFormat.of().parseHex(sha256));
      RestClient.create().put().uri(putUrl).contentType(MediaType.parseMediaType(upload.mediaType()))
          .headers(headers -> {
            headers.setContentLength(bytes.length);
            headers.set("x-amz-checksum-sha256", checksumSha256);
          }).body(bytes).retrieve().toBodilessEntity();
    } catch (RuntimeException failure) {
      throw new PlatformOperationException("ARTIFACT_UPLOAD_FAILED");
    }
    Map<?, ?> completed;
    try {
      completed = platform.post().uri("/internal/tasks/{taskId}/artifacts/{artifactId}/complete", taskId, artifactId)
          .header(HttpHeaders.AUTHORIZATION, bearer())
          .body(Map.of("reservationId", reservationId.toString(), "manifest", upload.manifest()))
          .retrieve().body(Map.class);
    } catch (RuntimeException failure) {
      throw new PlatformOperationException("ARTIFACT_COMPLETION_FAILED");
    }
    if (completed == null || !Boolean.TRUE.equals(completed.get("completed"))
        || !artifactId.equals(uuid(completed, "artifactId"))) {
      throw new PlatformOperationException("ARTIFACT_COMPLETION_FAILED");
    }
    return new PublishedArtifact(artifactId, reservationId,
        "artifact://" + upload.kind().referenceSegment + "/" + artifactId, sha256);
  }

  private static String idempotencyKey(PublicationKey key) {
    String canonical = key.taskId() + "\n" + key.name() + "\n" + key.kind() + "\n"
        + key.mediaType() + "\n" + key.sha256() + "\n" + key.manifestSha256();
    return sha256(canonical.getBytes(StandardCharsets.UTF_8));
  }

  private static Map<String, Object> options(Object value) {
    return objectMap(value, "PROVIDER_OPTIONS_INVALID");
  }

  private static Map<String, Object> objectMap(Object value, String errorCode) {
    if (value == null) return Map.of();
    if (value instanceof Map<?, ?> map) {
      var result = new java.util.LinkedHashMap<String, Object>();
      map.forEach((key, option) -> result.put(String.valueOf(key), option));
      return Map.copyOf(result);
    }
    if (value instanceof String json) {
      try { return JSON.readValue(json, STRING_OBJECT_MAP); }
      catch (JacksonException invalid) { throw new PlatformOperationException(errorCode); }
    }
    throw new PlatformOperationException(errorCode);
  }

  private static String value(Map<?, ?> map, String key) {
    Object value = map.get(key);
    return value == null ? null : String.valueOf(value);
  }

  private static UUID uuid(Map<?, ?> map, String key) {
    try { return UUID.fromString(requireText(value(map, key), "platform response is incomplete")); }
    catch (IllegalArgumentException invalid) { throw new PlatformOperationException("PLATFORM_RESPONSE_INVALID"); }
  }

  private static URI requireHttpUri(String value) {
    try {
      URI uri = URI.create(requireText(value, "URL is required"));
      if (!Set.of("http", "https").contains(uri.getScheme() == null ? "" : uri.getScheme().toLowerCase())
          || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
        throw new IllegalArgumentException("URL is invalid");
      }
      return uri;
    } catch (RuntimeException invalid) {
      if (invalid instanceof IllegalArgumentException argument && "URL is invalid".equals(argument.getMessage())) throw argument;
      throw new IllegalArgumentException("URL is invalid");
    }
  }

  private static String requireText(String value, String message) {
    if (value == null || value.isBlank() || "null".equals(value)) throw new IllegalArgumentException(message);
    return value;
  }

  private String bearer() { return "Bearer " + token; }

  private static String sha256(byte[] bytes) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
  }

  public enum ArtifactKind {
    DOC("docs"), HTML("site"), MANIFEST("manifests");
    private final String referenceSegment;
    ArtifactKind(String referenceSegment) { this.referenceSegment = referenceSegment; }
  }

  public record ArtifactUpload(String name, ArtifactKind kind, String mediaType, byte[] bytes, String manifest) {
    public ArtifactUpload {
      if (kind == null) throw new IllegalArgumentException("Agent artifact kind must be DOC, HTML or MANIFEST");
      if (name == null || name.isBlank() || name.startsWith("/") || name.contains("\\")
          || java.util.Arrays.asList(name.split("/")).contains("..") || name.length() > 240) {
        throw new IllegalArgumentException("artifact name is invalid");
      }
      String expectedType = switch (kind) {
        case DOC -> "text/markdown";
        case HTML -> "text/html";
        case MANIFEST -> "application/json";
      };
      MediaType actual;
      try { actual = MediaType.parseMediaType(requireText(mediaType, "artifact media type is required")); }
      catch (RuntimeException invalid) { throw new IllegalArgumentException("artifact media type is invalid"); }
      if (!MediaType.parseMediaType(expectedType).isCompatibleWith(actual)) {
        throw new IllegalArgumentException("artifact media type does not match its kind");
      }
      bytes = bytes == null ? null : bytes.clone();
      if (bytes == null || bytes.length == 0 || bytes.length > MAX_ARTIFACT_BYTES) {
        throw new IllegalArgumentException("artifact bytes are invalid");
      }
      manifest = requireText(manifest, "artifact manifest is required");
      if (manifest.getBytes(StandardCharsets.UTF_8).length > MAX_MANIFEST_BYTES) {
        throw new IllegalArgumentException("artifact manifest is too large");
      }
      try {
        JsonNode manifestJson = JSON.readTree(manifest);
        if (!manifestJson.isObject()) throw new IllegalArgumentException("artifact manifest must be a JSON object");
      } catch (JacksonException invalid) {
        throw new IllegalArgumentException("artifact manifest is invalid");
      }
    }
    @Override public byte[] bytes() { return bytes.clone(); }
  }

  public record PublishedArtifact(UUID artifactId, UUID reservationId, String reference, String sha256) { }
  private record PublicationKey(UUID taskId, String name, ArtifactKind kind, String mediaType,
                                String sha256, String manifestSha256) { }

  static final class PlatformOperationException extends IllegalStateException {
    PlatformOperationException(String code) { super(code); }
  }
}

interface AgentPlatformGateway {
  AgentTaskContext context(UUID taskId);
  ProviderConnection provider(UUID taskId);
  byte[] fetchSource(URI sourceUrl);
  AgentPlatformClient.PublishedArtifact publish(UUID taskId, AgentPlatformClient.ArtifactUpload upload);
}

record AgentTemplateContext(UUID versionId, String format, String markdown, String html,
                            String css, String parameterSchema) {
  AgentTemplateContext {
    Objects.requireNonNull(versionId, "template version id is required");
    if (format == null || format.isBlank()) throw new IllegalArgumentException("template format is required");
    markdown = markdown == null ? "" : markdown;
    html = html == null ? "" : html;
    css = css == null ? "" : css;
    parameterSchema = parameterSchema == null ? "{}" : parameterSchema;
  }
}

record AgentTaskContext(UUID taskId, URI sourceUrl, AgentTemplateContext template,
                        Map<String, Object> parameters, UUID providerProfileId, String modelId,
                        Set<String> loginProfileReferences, String baseUrl) {
  AgentTaskContext {
    Objects.requireNonNull(taskId, "task id is required");
    Objects.requireNonNull(sourceUrl, "source URL is required");
    Objects.requireNonNull(template, "template is required");
    parameters = Map.copyOf(parameters == null ? Map.of() : parameters);
    Objects.requireNonNull(providerProfileId, "provider profile id is required");
    if (modelId == null || modelId.isBlank()) throw new IllegalArgumentException("model id is required");
    loginProfileReferences = Set.copyOf(loginProfileReferences == null ? Set.of() : loginProfileReferences);
  }
}
