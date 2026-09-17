package studio.agent.worker;

import java.util.concurrent.Semaphore;

/**
 * Process-wide gate for provider calls made while reading source chunks.
 *
 * <p>The per-task executor controls how much work one task can submit. This
 * gate protects the provider when several tasks are running in the same
 * Worker process.</p>
 */
final class SourceReadLimiter {
  static final int DEFAULT_MAX_IN_FLIGHT = 100;
  private static final int MAX_CONFIGURED_IN_FLIGHT = 100;
  private static final SourceReadLimiter SHARED = new SourceReadLimiter(
      configured("SOURCE_READ_MAX_IN_FLIGHT", DEFAULT_MAX_IN_FLIGHT));

  private final Semaphore permits;
  private final int maxInFlight;

  SourceReadLimiter(int maxInFlight) {
    if (maxInFlight < 1 || maxInFlight > MAX_CONFIGURED_IN_FLIGHT) {
      throw new IllegalArgumentException("source read max in-flight is invalid");
    }
    this.maxInFlight = maxInFlight;
    this.permits = new Semaphore(maxInFlight, true);
  }

  static SourceReadLimiter shared() {
    return SHARED;
  }

  int maxInFlight() {
    return maxInFlight;
  }

  void acquire() throws InterruptedException {
    permits.acquire();
  }

  void release() {
    permits.release();
  }

  ArtifactGenerationService.ModelGateway wrap(ArtifactGenerationService.ModelGateway delegate) {
    if (delegate == null) throw new IllegalArgumentException("model is required");
    return prompt -> {
      try {
        acquire();
        try {
          return delegate.complete(prompt);
        } finally {
          release();
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new SourceReadException("SOURCE_READ_INTERRUPTED", interrupted);
      }
    };
  }

  private static int configured(String name, int fallback) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) return fallback;
    try {
      int parsed = Integer.parseInt(value.trim());
      return parsed < 1 || parsed > MAX_CONFIGURED_IN_FLIGHT ? fallback : parsed;
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }
}
