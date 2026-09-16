package studio.agent.browser.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.playwright.Playwright;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import studio.agent.browser.security.NavigationPolicy;

class PlaywrightBrowserServiceTest {
  private static HttpServer fixture;
  private static URI baseUrl;

  @BeforeAll
  static void startFixture() throws IOException {
    fixture = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    fixture.createContext("/login", PlaywrightBrowserServiceTest::handleLogin);
    fixture.createContext("/auth/login", PlaywrightBrowserServiceTest::handleLogin);
    fixture.createContext("/async-login", exchange -> respond(exchange, 200, """
        <main><h1>Sign in</h1><form>
        <label>Email <input name='email' type='email'></label>
        <label>Password <input name='password' type='password'></label>
        <button type='submit'>Sign in</button></form>
        <script>document.querySelector('form').addEventListener('submit', event => {
          event.preventDefault();
          setTimeout(() => { document.cookie = 'fixture-role=admin; Path=/'; window.location = '/app'; }, 800);
        });</script></main>
        """));
    fixture.createContext("/app", exchange -> {
      String role = cookie(exchange, "fixture-role");
      if (role == null) { respond(exchange, 401, "<h1>Unauthorized</h1>"); return; }
      respond(exchange, 200, page(role, "Home", "Ready"));
    });
    fixture.createContext("/reports/admin", exchange -> {
      if (!"admin".equals(cookie(exchange, "fixture-role"))) { respond(exchange, 403, "<h1>Forbidden</h1>"); return; }
      respond(exchange, 200, page("admin", "Audit report", "Export ready"));
    });
    fixture.createContext("/reports/member", exchange -> {
      if (!"member".equals(cookie(exchange, "fixture-role"))) { respond(exchange, 403, "<h1>Forbidden</h1>"); return; }
      respond(exchange, 200, page("member", "My activity", "Profile ready"));
    });
    fixture.createContext("/missing", exchange -> respond(exchange, 404, "<h1>Missing page</h1>"));
    fixture.createContext("/delayed", exchange -> respond(exchange, 200, """
        <main><h1>Delayed controls</h1>
        <script>setTimeout(() => { const button = document.createElement('button'); button.textContent = 'Delayed action'; document.body.append(button); }, 250);</script>
        </main>
        """));
    fixture.start();
    baseUrl = URI.create("http://localhost:" + fixture.getAddress().getPort());
  }

  @AfterAll
  static void stopFixture() {
    fixture.stop(0);
  }

  @Test
  void separateProfilesUseIsolatedContextsAndCaptureCorrectMarkerPages() {
    var uploads = new ArrayList<PublishedArtifact>();
    try (Playwright playwright = Playwright.create()) {
      var service = service(playwright, profileCredentials(), artifact -> uploads.add(artifact));
      UUID taskId = UUID.randomUUID();
      UUID admin = service.open(new OpenSessionCommand(taskId, baseUrl, "admin")).sessionId();
      UUID member = service.open(new OpenSessionCommand(taskId, baseUrl, "member")).sessionId();

      assertThat(service.login(admin, taskId).status()).isEqualTo(OperationStatus.SUCCEEDED);
      assertThat(service.login(member, taskId).status()).isEqualTo(OperationStatus.SUCCEEDED);
      assertThat(service.snapshot(admin, taskId).snapshot()).contains("admin workspace").doesNotContain("admin-secret");
      assertThat(service.snapshot(member, taskId).snapshot()).contains("member workspace").doesNotContain("admin workspace");

      service.click(admin, new ClickCommand(taskId, LocatorSpec.role("menuitem", "Reports"), ExpectedState.none()));
      service.click(admin, new ClickCommand(taskId, LocatorSpec.role("link", "Audit"),
          new ExpectedState("/reports/admin", "Audit report")));
      var adminShot = service.screenshot(admin,
          new ScreenshotCommand(taskId, "marker-admin", "Admin audit", "admin.png",
              new ExpectedState("/reports/admin", "Export ready")));

      service.click(member, new ClickCommand(taskId, LocatorSpec.role("button", "Reports"), ExpectedState.none()));
      service.click(member, new ClickCommand(taskId, LocatorSpec.role("link", "Activity"),
          new ExpectedState("/reports/member", "My activity")));
      var memberShot = service.screenshot(member,
          new ScreenshotCommand(taskId, "marker-member", "Member activity", "member.png",
              new ExpectedState("/reports/member", "Profile ready")));

      assertThat(adminShot).extracting(ScreenshotResult::markerId, ScreenshotResult::profileReference,
          ScreenshotResult::status).containsExactly("marker-admin", "admin", OperationStatus.SUCCEEDED);
      assertThat(memberShot.reachedUrl()).endsWith("/reports/member");
      assertThat(uploads).hasSize(2).allSatisfy(upload -> {
        assertThat(upload.bytes()).startsWith((byte) 0x89, (byte) 0x50, (byte) 0x4e, (byte) 0x47);
        assertThat(upload.sha256()).hasSize(64);
      });
      service.closeAll();
    }
  }

  @Test
  void returnsUsefulMarkerAndActionFailureCodesWithoutSecrets() {
    try (Playwright playwright = Playwright.create()) {
      var service = service(playwright, profileCredentials(), artifact -> {});
      UUID taskId = UUID.randomUUID();
      UUID session = service.open(new OpenSessionCommand(taskId, baseUrl, "bad-profile")).sessionId();

      var login = service.login(session, taskId);
      assertThat(login.status()).isEqualTo(OperationStatus.FAILED);
      assertThat(login.error()).extracting(BrowserError::code).isEqualTo("LOGIN_REJECTED");
      assertThat(login.trace().toString()).doesNotContain("wrong-secret");

      UUID admin = service.open(new OpenSessionCommand(taskId, baseUrl, "admin")).sessionId();
      service.login(admin, taskId);
      var click = service.click(admin,
          new ClickCommand(taskId, LocatorSpec.role("button", "Unreachable button"), ExpectedState.none()));
      assertThat(click.error()).extracting(BrowserError::code).isEqualTo("LOCATOR_NOT_FOUND");

      var missing = service.navigate(admin,
          new NavigateCommand(taskId, "/missing", new ExpectedState("/missing", "Expected content")));
      assertThat(missing.error()).extracting(BrowserError::code).isEqualTo("TARGET_HTTP_ERROR");
      service.closeAll();
    }
  }

  @Test
  void rejectsTaskMismatchBeforeBrowserAction() {
    try (Playwright playwright = Playwright.create()) {
      var service = service(playwright, profileCredentials(), artifact -> {});
      UUID taskId = UUID.randomUUID();
      UUID session = service.open(new OpenSessionCommand(taskId, baseUrl, "admin")).sessionId();
      assertThatThrownBy(() -> service.snapshot(session, UUID.randomUUID()))
          .isInstanceOf(SessionAccessException.class).hasMessageContaining("SESSION_TASK_MISMATCH");
      service.closeAll();
    }
  }

  @Test
  void waitsForAnAsynchronouslyRenderedUniqueLocatorBeforeClicking() {
    try (Playwright playwright = Playwright.create()) {
      var service = service(playwright, profileCredentials(), artifact -> {});
      UUID taskId = UUID.randomUUID();
      UUID session = service.open(new OpenSessionCommand(taskId, baseUrl, "admin")).sessionId();
      service.navigate(session, new NavigateCommand(taskId, "/delayed", ExpectedState.none()));

      var click = service.click(session,
          new ClickCommand(taskId, LocatorSpec.role("button", "Delayed action"), ExpectedState.none()));

      assertThat(click.status()).isEqualTo(OperationStatus.SUCCEEDED);
      assertThat(click.trace()).anyMatch(value -> value.startsWith("LOCATOR_MATCHES:1"));
      service.closeAll();
    }
  }

  @Test
  void waitsForAsyncLoginRedirectBeforeValidatingThePostLoginState() {
    try (Playwright playwright = Playwright.create()) {
      var service = service(playwright, profileCredentials(), artifact -> {});
      UUID taskId = UUID.randomUUID();
      UUID session = service.open(new OpenSessionCommand(taskId, baseUrl, "async")).sessionId();

      var login = service.login(session, taskId);

      assertThat(login.status()).isEqualTo(OperationStatus.SUCCEEDED);
      assertThat(login.reachedUrl()).endsWith("/app");
      service.closeAll();
    }
  }

  private static PlaywrightBrowserService service(Playwright playwright,
      Map<String, LoginCredential> credentials, ArtifactPublisher publisher) {
    var policy = new NavigationPolicy(true, List.of("localhost"), List.of(),
        host -> List.of(InetAddress.getByName("127.0.0.1")));
    var limits = new BrowserLimits(Duration.ofSeconds(3), Duration.ofSeconds(5), 1, 1280, 720,
        5_000_000, 20_000_000);
    return new PlaywrightBrowserService(playwright.chromium().launch(), policy,
        new SessionRegistry(8, Duration.ofMinutes(5), Clock.systemUTC()),
        (taskId, reference) -> credentials.get(reference), publisher, limits);
  }

  private static Map<String, LoginCredential> profileCredentials() {
    return Map.of(
        "admin", new LoginCredential("/auth/login", "/app", "admin@example.test", "admin-secret",
            LocatorSpec.label("Email"), LocatorSpec.label("Password"), LocatorSpec.role("button", "Sign in"),
            new ExpectedState("/app", "admin workspace")),
        "member", new LoginCredential("/login", "/app", "member@example.test", "member-secret",
            LocatorSpec.label("Email"), LocatorSpec.label("Password"), LocatorSpec.role("button", "Sign in"),
            new ExpectedState("/app", "member workspace")),
        "async", new LoginCredential("/async-login", "/app", "admin@example.test", "admin-secret",
            LocatorSpec.label("Email"), LocatorSpec.label("Password"), LocatorSpec.role("button", "Sign in"),
            new ExpectedState("/app", "admin workspace")),
        "bad-profile", new LoginCredential("/auth/login", "/app", "admin@example.test", "wrong-secret",
            LocatorSpec.label("Email"), LocatorSpec.label("Password"), LocatorSpec.role("button", "Sign in"),
            new ExpectedState("/app", "admin workspace")));
  }

  private static String page(String role, String heading, String state) {
    return """
        <main><h1>%s</h1><p>%s workspace</p><p>%s</p>
        <button type='button' role='menuitem' onclick="document.getElementById('submenu').hidden=false">Reports</button>
        <nav id='submenu' aria-label='Report submenu'>
          <button type='button' role='menuitem'>Reports overview</button>
          <a href='/reports/admin'>Audit</a><a href='/reports/member'>Activity</a>
        </nav><form><label>Display name <input value='%s user'></label></form></main>
        """.formatted(heading, role, state, role);
  }

  private static String cookie(HttpExchange exchange, String name) {
    return exchange.getRequestHeaders().getOrDefault("Cookie", List.of()).stream()
        .flatMap(value -> List.of(value.split(";[ ]*")).stream())
        .filter(value -> value.startsWith(name + "=")).map(value -> value.substring(name.length() + 1))
        .findFirst().orElse(null);
  }

  private static void handleLogin(HttpExchange exchange) throws IOException {
    if (exchange.getRequestMethod().equals("POST")) {
      String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      String role = body.contains("admin%40example.test") && body.contains("admin-secret") ? "admin"
          : body.contains("member%40example.test") && body.contains("member-secret") ? "member" : null;
      if (role == null) {
        respond(exchange, 401, "<h1>Login failed</h1><p>Invalid credentials</p>");
      } else {
        exchange.getResponseHeaders().add("Set-Cookie", "fixture-role=" + role + "; Path=/; HttpOnly; SameSite=Lax");
        exchange.getResponseHeaders().add("Location", "/app");
        exchange.sendResponseHeaders(303, -1);
        exchange.close();
      }
      return;
    }
    respond(exchange, 200, """
        <main><h1>Sign in</h1><form method='post'>
        <label>Email <input name='email' type='email'></label>
        <label>Password <input name='password' type='password'></label>
        <button type='submit'>Sign in</button></form></main>
        """);
  }

  private static void redirect(HttpExchange exchange, String path) throws IOException {
    exchange.getResponseHeaders().add("Location", path);
    exchange.sendResponseHeaders(303, -1);
    exchange.close();
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}
