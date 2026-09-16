package studio.agent.browser.session;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.SelectOption;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import studio.agent.browser.security.NavigationPolicy;
import studio.agent.browser.security.NavigationRejectedException;

/** Executes only declarative, semantic browser operations; never evaluates user supplied JavaScript. */
public final class PlaywrightBrowserService implements AutoCloseable {
  private record RuntimeSession(BrowserContext context, Page page, List<String> trace, Set<String> secrets) {}

  private final Browser browser;
  private final Playwright playwright;
  private final NavigationPolicy policy;
  private final SessionRegistry registry;
  private final LoginCredentialResolver credentials;
  private final ArtifactPublisher artifacts;
  private final BrowserLimits limits;
  private final Map<UUID, RuntimeSession> runtimes = new ConcurrentHashMap<>();

  public PlaywrightBrowserService(Browser browser, NavigationPolicy policy, SessionRegistry registry,
      LoginCredentialResolver credentials, ArtifactPublisher artifacts, BrowserLimits limits) {
    this(null, browser, policy, registry, credentials, artifacts, limits);
  }

  public PlaywrightBrowserService(Playwright playwright, Browser browser, NavigationPolicy policy,
      SessionRegistry registry, LoginCredentialResolver credentials, ArtifactPublisher artifacts, BrowserLimits limits) {
    this.playwright = playwright; this.browser = browser; this.policy = policy; this.registry = registry;
    this.credentials = credentials; this.artifacts = artifacts; this.limits = limits;
  }

  public OpenSessionResult open(OpenSessionCommand command) {
    BrowserContext context = null;
    try {
      policy.validate(command.baseUrl(), null);
      context = browser.newContext(new Browser.NewContextOptions()
          .setViewportSize(limits.viewportWidth(), limits.viewportHeight())
          .setAcceptDownloads(false));
      Page page = context.newPage();
      context.route("**/*", route -> { try { policy.validate(URI.create(route.request().url()), command.baseUrl()); route.resume(); } catch (RuntimeException rejected) { route.abort(); } });
      context.setDefaultTimeout(limits.actionTimeout().toMillis());
      context.setDefaultNavigationTimeout(limits.navigationTimeout().toMillis());
      var registrationId = new AtomicReference<UUID>();
      var registration = registry.register(command.taskId(), command.baseUrl(), command.loginProfileReference(), ignored -> {
        RuntimeSession session = runtimes.remove(registrationId.get());
        if (session != null) { try { session.context().clearCookies(); } catch (RuntimeException ignored2) {} session.context().close(); }
      });
      registrationId.set(registration.id());
      runtimes.put(registration.id(), new RuntimeSession(context, page, new ArrayList<>(List.of("SESSION_OPENED")), java.util.concurrent.ConcurrentHashMap.newKeySet()));
      return new OpenSessionResult(registration.id(), command.taskId(), command.loginProfileReference(), OperationStatus.SUCCEEDED, null);
    } catch (RuntimeException exception) { if (context != null) try { context.close(); } catch (RuntimeException ignored) {} return openFailure(command, exception); }
  }

  private OpenSessionResult openFailure(OpenSessionCommand c, RuntimeException e) {
    return new OpenSessionResult(null, c.taskId(), c.loginProfileReference(), OperationStatus.FAILED, error(e));
  }

  public OperationResult login(UUID sessionId, UUID taskId) {
    OperationResult result = execute(sessionId, taskId, "LOGIN", ExpectedState.none(), runtime -> {
      var session = registry.require(sessionId, taskId);
      LoginCredential credential = credentials.resolve(taskId, session.profileReference());
      if (credential == null) throw new BrowserActionException("LOGIN_PROFILE_NOT_FOUND", "login profile could not be resolved");
      runtime.secrets().add(credential.username()); runtime.secrets().add(credential.password());
      URI target = resolve(session.baseUrl(), credential.loginPath());
      navigate(runtime.page(), target, session.baseUrl());
      locator(runtime.page(), credential.usernameLocator()).fill(credential.username());
      locator(runtime.page(), credential.passwordLocator()).fill(credential.password());
      locator(runtime.page(), credential.submitLocator()).click();
      verify(runtime.page(), credential.expected(), session.baseUrl());
    });
    if (result.status() == OperationStatus.FAILED && ("EXPECTED_URL_NOT_REACHED".equals(result.error().code()) || "EXPECTED_STATE_NOT_REACHED".equals(result.error().code()))) {
      return new OperationResult(OperationStatus.FAILED, result.reachedUrl(), result.snapshot(), result.trace(), new BrowserError("LOGIN_REJECTED", "login credentials were rejected or did not reach the expected state"));
    }
    return result;
  }

  public OperationResult snapshot(UUID sessionId, UUID taskId) {
    var session = registry.require(sessionId, taskId); RuntimeSession runtime = runtime(sessionId);
    String snapshot = redactedSnapshot(runtime.page(), runtime.secrets());
    return new OperationResult(OperationStatus.SUCCEEDED, runtime.page().url(), snapshot, List.copyOf(runtime.trace()), null);
  }

  public OperationResult navigate(UUID id, NavigateCommand c) {
    return execute(id, c.taskId(), "NAVIGATE", c.expected(), runtime -> {
      var session = registry.require(id, c.taskId()); navigate(runtime.page(), resolve(session.baseUrl(), c.target()), session.baseUrl());
    });
  }
  public OperationResult click(UUID id, ClickCommand c) { return execute(id, c.taskId(), "CLICK:" + safe(c.locator()), c.expected(), r -> locator(r.page(), c.locator()).click()); }
  public OperationResult fill(UUID id, FillCommand c) { return execute(id, c.taskId(), "FILL:" + safe(c.locator()), c.expected(), r -> locator(r.page(), c.locator()).fill(c.value())); }
  public OperationResult select(UUID id, SelectCommand c) { return execute(id, c.taskId(), "SELECT:" + safe(c.locator()), c.expected(), r -> locator(r.page(), c.locator()).selectOption(new SelectOption().setValue(c.value()))); }
  public OperationResult check(UUID id, CheckCommand c) { return execute(id, c.taskId(), "CHECK:" + safe(c.locator()), c.expected(), r -> locator(r.page(), c.locator()).check()); }
  public OperationResult uncheck(UUID id, CheckCommand c) { return execute(id, c.taskId(), "UNCHECK:" + safe(c.locator()), c.expected(), r -> locator(r.page(), c.locator()).uncheck()); }
  public OperationResult waitFor(UUID id, WaitCommand c) { return execute(id, c.taskId(), "WAIT:" + safe(c.locator()), c.expected(), r -> locator(r.page(), c.locator()).waitFor()); }

  public ScreenshotResult screenshot(UUID id, ScreenshotCommand c) {
    OperationResult validation = execute(id, c.taskId(), "SCREENSHOT:" + c.markerId(), c.expected(), runtime -> {});
    var session = registry.require(id, c.taskId()); RuntimeSession runtime = runtime(id);
    if (validation.status() == OperationStatus.FAILED) return new ScreenshotResult(c.markerId(), session.profileReference(), validation.reachedUrl(), null, OperationStatus.FAILED, validation.error(), validation.trace());
    try {
      byte[] bytes = runtime.page().screenshot(new Page.ScreenshotOptions().setFullPage(true));
      if (bytes.length == 0 || bytes.length > limits.maxScreenshotBytes()) throw new BrowserActionException("SCREENSHOT_SIZE_INVALID", "screenshot size is outside configured limit");
      PublishedArtifact artifact = new PublishedArtifact("artifact://" + c.taskId() + "/" + c.markerId() + "/" + safeFilename(c.filename()), bytes, sha256(bytes));
      artifacts.publish(artifact);
      runtime.trace().add("SCREENSHOT_CAPTURED:" + c.markerId());
      return new ScreenshotResult(c.markerId(), session.profileReference(), runtime.page().url(), artifact.reference(), OperationStatus.SUCCEEDED, null, List.copyOf(runtime.trace()));
    } catch (RuntimeException exception) {
      return new ScreenshotResult(c.markerId(), session.profileReference(), runtime.page().url(), null, OperationStatus.FAILED, error(exception), List.copyOf(runtime.trace()));
    }
  }

  public void close(UUID id, UUID taskId) { registry.remove(id, taskId); }
  public void closeAll() { registry.closeAll(); try { browser.close(); } finally { if (playwright != null) playwright.close(); } }
  @Override public void close() { closeAll(); }

  private OperationResult execute(UUID id, UUID taskId, String action, ExpectedState expected, ThrowingAction operation) {
    var session = registry.require(id, taskId); RuntimeSession runtime = runtime(id);
    try {
      for (int attempt = 1; ; attempt++) try {
        operation.run(runtime); verify(runtime.page(), expected, session.baseUrl()); runtime.trace().add(action + ":OK");
        return new OperationResult(OperationStatus.SUCCEEDED, runtime.page().url(), redactedSnapshot(runtime.page(), runtime.secrets()), List.copyOf(runtime.trace()), null);
      } catch (RuntimeException exception) {
        if (attempt >= limits.attempts()) throw exception;
        runtime.trace().add(action + ":RETRY");
      }
    } catch (RuntimeException exception) {
      runtime.trace().add(action + ":FAILED:" + error(exception).code());
      return new OperationResult(OperationStatus.FAILED, runtime.page().url(), redactedSnapshot(runtime.page(), runtime.secrets()), List.copyOf(runtime.trace()), error(exception));
    }
  }
  private RuntimeSession runtime(UUID id) { RuntimeSession value = runtimes.get(id); if (value == null) throw new SessionAccessException("SESSION_NOT_FOUND"); return value; }
  private void navigate(Page page, URI target, URI base) { policy.validate(target, base); Response response = page.navigate(target.toString()); if (response != null && response.status() >= 400) throw new BrowserActionException("TARGET_HTTP_ERROR", "target returned an error response"); policy.validate(URI.create(page.url()), base); }
  private void verify(Page page, ExpectedState expected, URI base) {
    policy.validate(URI.create(page.url()), base);
    if (expected == null) return;
    if (expected.path() != null && !URI.create(page.url()).getPath().equals(expected.path())) throw new BrowserActionException("EXPECTED_URL_NOT_REACHED", "expected route was not reached");
    if (expected.requiredText() != null && !page.locator("body").ariaSnapshot().contains(expected.requiredText())) throw new BrowserActionException("EXPECTED_STATE_NOT_REACHED", "expected accessibility state was not reached");
  }
  private Locator locator(Page page, LocatorSpec spec) {
    if (spec == null || spec.name() == null || spec.name().isBlank()) throw new BrowserActionException("LOCATOR_INVALID", "a semantic locator is required");
    return switch (spec.kind()) {
      case "label" -> page.getByLabel(spec.name()); case "test-id" -> page.getByTestId(spec.name());
      case "role" -> page.getByRole(AriaRole.valueOf(spec.role().toUpperCase().replace('-', '_')), new Page.GetByRoleOptions().setName(spec.name()));
      default -> throw new BrowserActionException("LOCATOR_INVALID", "unsupported locator kind");
    };
  }
  private static URI resolve(URI base, String target) { try { return base.resolve(target); } catch (RuntimeException e) { throw new BrowserActionException("INVALID_TARGET_URL", "target URL is invalid"); } }
  private static String redactedSnapshot(Page page, Set<String> secrets) { return SnapshotRedactor.redact(page.locator("body").ariaSnapshot(), secrets); }
  private static String safe(LocatorSpec spec) { return spec == null ? "invalid" : spec.kind() + ":" + spec.role() + ":" + spec.name(); }
  private static String safeFilename(String name) { if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}\\.png")) throw new BrowserActionException("INVALID_FILENAME", "screenshot filename must be a simple .png name"); return name; }
  private static String sha256(byte[] bytes) {
    try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
  }
  private static BrowserError error(RuntimeException e) { if (e instanceof BrowserActionException b) return new BrowserError(b.code, b.message); if (e instanceof NavigationRejectedException n) return new BrowserError(n.code(), "navigation rejected"); if (e instanceof SessionAccessException s) return new BrowserError(s.code(), "session access rejected"); if (e.getClass().getName().startsWith("com.microsoft.playwright")) return new BrowserError("LOCATOR_NOT_FOUND", "semantic locator was not found or actionable"); return new BrowserError("BROWSER_ACTION_FAILED", "browser action failed"); }
  @FunctionalInterface private interface ThrowingAction { void run(RuntimeSession runtime); }
  private static final class BrowserActionException extends RuntimeException { final String code; final String message; BrowserActionException(String code, String message) { this.code = code; this.message = message; } }
}
