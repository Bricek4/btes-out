package studio.agent.browser.session;
import java.time.Duration;
public record BrowserLimits(Duration actionTimeout, Duration navigationTimeout, int attempts, int viewportWidth, int viewportHeight, long maxScreenshotBytes, long maxDownloadBytes) {}
