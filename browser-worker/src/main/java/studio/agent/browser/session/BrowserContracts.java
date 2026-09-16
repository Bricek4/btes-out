package studio.agent.browser.session;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

enum OperationStatus { SUCCEEDED, FAILED }

record BrowserError(String code, String message) {}

record ExpectedState(String path, String requiredText) {
  static ExpectedState none() { return new ExpectedState(null, null); }
}

record LocatorSpec(String kind, String role, String name) {
  static LocatorSpec role(String role, String name) { return new LocatorSpec("role", role, name); }
  static LocatorSpec label(String name) { return new LocatorSpec("label", null, name); }
  static LocatorSpec testId(String name) { return new LocatorSpec("test-id", null, name); }
}

record OpenSessionCommand(UUID taskId, URI baseUrl, String loginProfileReference) {}
record OpenSessionResult(UUID sessionId, UUID taskId, String profileReference, OperationStatus status, BrowserError error) {}
record ClickCommand(UUID taskId, LocatorSpec locator, ExpectedState expected) {}
record NavigateCommand(UUID taskId, String target, ExpectedState expected) {}
record FillCommand(UUID taskId, LocatorSpec locator, String value, ExpectedState expected) {}
record SelectCommand(UUID taskId, LocatorSpec locator, String value, ExpectedState expected) {}
record CheckCommand(UUID taskId, LocatorSpec locator, ExpectedState expected) {}
record WaitCommand(UUID taskId, LocatorSpec locator, ExpectedState expected) {}
record ScreenshotCommand(UUID taskId, String markerId, String caption, String filename, ExpectedState expected) {}
record OperationResult(OperationStatus status, String reachedUrl, String snapshot, List<String> trace, BrowserError error) {}
record ScreenshotResult(String markerId, String profileReference, String reachedUrl, String artifactReference,
                        OperationStatus status, BrowserError error, List<String> trace) {}

record LoginCredential(String loginPath, String username, String password, LocatorSpec usernameLocator,
                       LocatorSpec passwordLocator, LocatorSpec submitLocator, ExpectedState expected) {}
record BrowserLimits(Duration actionTimeout, Duration navigationTimeout, int attempts, int viewportWidth,
                     int viewportHeight, long maxScreenshotBytes, long maxDownloadBytes) {}
record PublishedArtifact(String reference, byte[] bytes, String sha256) {}

@FunctionalInterface
interface LoginCredentialResolver { LoginCredential resolve(UUID taskId, String profileReference); }

@FunctionalInterface
interface ArtifactPublisher { void publish(PublishedArtifact artifact); }
