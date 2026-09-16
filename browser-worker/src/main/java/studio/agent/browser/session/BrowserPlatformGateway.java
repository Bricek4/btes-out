package studio.agent.browser.session;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import studio.agent.contracts.LoginLocator;
import tools.jackson.databind.ObjectMapper;

/** Browser-token-only adapter: credentials remain here and are not returned by session APIs. */
@Component
public final class BrowserPlatformGateway implements LoginCredentialResolver, ArtifactPublisher {
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
  private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private final ObjectMapper json = new ObjectMapper(); private final URI platform; private final String token;
  public BrowserPlatformGateway(@Value("${PLATFORM_API_URL}") String platform, @Value("${BROWSER_WORKER_TOKEN}") String token) { this.platform=URI.create(platform); this.token=token; }
  @Override public LoginCredential resolve(UUID taskId, String profileReference) {
    try {
      Map<?,?> context = get("/internal/worker-context/browser/" + taskId);
      Object profiles = context.get("loginProfiles"); UUID profileId = null;
      if (profiles instanceof List<?> rows) for (Object row : rows) if (row instanceof Map<?,?> values && profileReference.equals(String.valueOf(values.get("reference")))) { profileId=UUID.fromString(String.valueOf(values.get("id"))); break; }
      if (profileId == null) throw new IllegalArgumentException("login profile is not present in task browser context");
      Map<?,?> credential = post("/internal/tasks/" + taskId + "/login-profiles/" + profileId + "/credential", Map.of());
      return new LoginCredential(String.valueOf(credential.get("loginPath")), String.valueOf(credential.get("username")), String.valueOf(credential.get("password")),
          locator(credential.get("usernameLocator")), locator(credential.get("passwordLocator")), locator(credential.get("submitLocator")), ExpectedState.none());
    } catch (Exception e) { throw new IllegalStateException("login credential resolution failed"); }
  }
  @Override public void publish(PublishedArtifact artifact) {
    try {
      URI ref = URI.create(artifact.reference()); String[] parts = ref.getPath().split("/"); UUID taskId=UUID.fromString(ref.getHost());
      String key = sha256(taskId + "\\n" + parts[1] + "\\n" + artifact.sha256());
      Map<?,?> reserved=post("/internal/tasks/"+taskId+"/artifacts/presign", Map.of("name",parts[2],"kind","SCREENSHOT","mediaType","image/png","sizeBytes",artifact.bytes().length,"sha256",artifact.sha256(),"idempotencyKey",key));
      if (!key.equals(String.valueOf(reserved.get("idempotencyKey")))) throw new IllegalStateException("ARTIFACT_RESERVATION_INVALID");
      put(URI.create(String.valueOf(reserved.get("putUrl"))), artifact.bytes(), artifact.sha256());
      post("/internal/tasks/"+taskId+"/artifacts/"+reserved.get("artifactId")+"/complete", Map.of("manifest","{}", "reservationId", String.valueOf(reserved.get("reservationId"))));
    } catch (Exception e) { throw new IllegalStateException("ARTIFACT_PUBLICATION_FAILED"); }
  }
  private Map<?,?> get(String path) throws Exception { return send(HttpRequest.newBuilder(platform.resolve(path)).timeout(REQUEST_TIMEOUT).header("Authorization","Bearer "+token).GET().build()); }
  private Map<?,?> post(String path,Object body) throws Exception { return send(HttpRequest.newBuilder(platform.resolve(path)).timeout(REQUEST_TIMEOUT).header("Authorization","Bearer "+token).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build()); }
  private Map<?,?> send(HttpRequest request) throws Exception { var response=http.send(request,HttpResponse.BodyHandlers.ofByteArray()); if(response.body().length>MAX_RESPONSE_BYTES) throw new IllegalStateException("PLATFORM_RESPONSE_TOO_LARGE"); if(response.statusCode()/100!=2) throw new IllegalStateException("PLATFORM_REQUEST_REJECTED"); return json.readValue(response.body(),Map.class); }
  private void put(URI url,byte[] bytes,String sha256) throws Exception { String checksum=java.util.Base64.getEncoder().encodeToString(java.util.HexFormat.of().parseHex(sha256)); var response=http.send(HttpRequest.newBuilder(url).timeout(REQUEST_TIMEOUT).header("x-amz-checksum-sha256",checksum).PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),HttpResponse.BodyHandlers.discarding()); if(response.statusCode()/100!=2) throw new IllegalStateException("ARTIFACT_UPLOAD_REJECTED"); }
  private LocatorSpec locator(Object value) throws tools.jackson.core.JacksonException { LoginLocator locator=json.convertValue(value, LoginLocator.class); return new LocatorSpec(locator.kind(), locator.role(), locator.name()); }
  private static String sha256(String input) { try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8))); } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); } }
}
