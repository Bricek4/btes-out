package studio.agent.browser.session;
import java.time.Duration;

/** Bounded browser execution settings. Snapshot work has its own shorter deadline because it is diagnostic. */
public record BrowserLimits(Duration actionTimeout, Duration navigationTimeout, int attempts,
                            int viewportWidth, int viewportHeight, long maxScreenshotBytes,
                            long maxDownloadBytes, Duration snapshotTimeout) {
  public BrowserLimits(Duration actionTimeout, Duration navigationTimeout, int attempts,
                       int viewportWidth, int viewportHeight, long maxScreenshotBytes,
                       long maxDownloadBytes) {
    this(actionTimeout, navigationTimeout, attempts, viewportWidth, viewportHeight,
        maxScreenshotBytes, maxDownloadBytes, Duration.ofSeconds(3));
  }

  public BrowserLimits {
    if (snapshotTimeout == null || snapshotTimeout.isZero() || snapshotTimeout.isNegative()) {
      throw new IllegalArgumentException("snapshot timeout must be positive");
    }
  }
}
