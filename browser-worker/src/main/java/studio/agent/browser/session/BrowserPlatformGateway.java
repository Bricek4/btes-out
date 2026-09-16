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
import tools.jackson.databind.ObjectMapper;

/** Browser-token-only adapter: credentials remain here and are not returned by session APIs. */
@Component
public final class BrowserPlatformGateway implements LoginCredentialResolver, ArtifactPublisher {
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
          parseLocator(String.valueOf(credential.get("usernameLocator"))), parseLocator(String.valueOf(credential.get("passwordLocator"))), parseLocator(String.valueOf(credential.get("submitLocator"))), ExpectedState.none());
    } catch (Exception e) { throw new IllegalStateException("login credential resolution failed"); }
  }
  @Override public void publish(PublishedArtifact artifact) {
    try {
      URI ref = URI.create(artifact.reference()); String[] parts = ref.getPath().split("/"); UUID taskId=UUID.fromString(ref.getHost());
      Map<?,?> reserved=post("/internal/tasks/"+taskId+"/artifacts/presign", Map.of("name",parts[2],"kind","SCREENSHOT","mediaType","image/png","sizeBytes",artifact.bytes().length,"sha256",artifact.sha256()));
      put(URI.create(String.valueOf(reserved.get("putUrl"))), artifact.bytes());
      post("/internal/tasks/"+taskId+"/artifacts/"+reserved.get("artifactId")+"/complete", Map.of("manifest","{}"));
    } catch (Exception e) { throw new IllegalStateException("screenshot artifact publication failed"); }
  }
  private Map<?,?> get(String path) throws Exception { return send(HttpRequest.newBuilder(platform.resolve(path)).header("Authorization","Bearer "+token).GET().build()); }
  private Map<?,?> post(String path,Object body) throws Exception { return send(HttpRequest.newBuilder(platform.resolve(path)).header("Authorization","Bearer "+token).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build()); }
  private Map<?,?> send(HttpRequest request) throws Exception { var response=http.send(request,HttpResponse.BodyHandlers.ofByteArray()); if(response.statusCode()/100!=2) throw new IllegalStateException("platform request rejected"); return json.readValue(response.body(),Map.class); }
  private void put(URI url,byte[] bytes) throws Exception { var response=http.send(HttpRequest.newBuilder(url).header("Content-Type","image/png").PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),HttpResponse.BodyHandlers.discarding()); if(response.statusCode()/100!=2) throw new IllegalStateException("artifact upload rejected"); }
  private static LocatorSpec parseLocator(String value) { String[] p=value.split(":",3); if(p.length==3&&"role".equals(p[0]))return LocatorSpec.role(p[1],p[2]); if(p.length==2&&"label".equals(p[0]))return LocatorSpec.label(p[1]); if(p.length==2&&"test-id".equals(p[0]))return LocatorSpec.testId(p[1]); return LocatorSpec.label(value); }
}
