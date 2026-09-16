package studio.agent.browser.session;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class SessionRegistry {
  public record RegisteredSession(UUID id, UUID taskId, URI baseUrl, String profileReference,
                                  Instant createdAt, Instant expiresAt, Consumer<Boolean> closeAction) {}

  private final int maxContexts;
  private final Duration ttl;
  private final Clock clock;
  private final Map<UUID, RegisteredSession> sessions = new ConcurrentHashMap<>();

  public SessionRegistry(int maxContexts, Duration ttl, Clock clock) {
    if (maxContexts < 1 || ttl.isNegative() || ttl.isZero()) {
      throw new IllegalArgumentException("session limits must be positive");
    }
    this.maxContexts = maxContexts;
    this.ttl = ttl;
    this.clock = clock;
  }

  public synchronized RegisteredSession register(UUID taskId, URI baseUrl, String profileReference,
      Consumer<Boolean> closeAction) {
    purgeExpired();
    if (sessions.size() >= maxContexts) {
      throw new SessionAccessException("SESSION_LIMIT_REACHED");
    }
    Instant now = clock.instant();
    var session = new RegisteredSession(UUID.randomUUID(), taskId, baseUrl, profileReference,
        now, now.plus(ttl), closeAction);
    sessions.put(session.id(), session);
    return session;
  }

  public RegisteredSession require(UUID sessionId, UUID taskId) {
    RegisteredSession session = sessions.get(sessionId);
    if (session == null) {
      throw new SessionAccessException("SESSION_NOT_FOUND");
    }
    if (!session.taskId().equals(taskId)) {
      throw new SessionAccessException("SESSION_TASK_MISMATCH");
    }
    if (!session.expiresAt().isAfter(clock.instant())) {
      removeInternal(session);
      throw new SessionAccessException("SESSION_EXPIRED");
    }
    return session;
  }

  public synchronized void remove(UUID sessionId, UUID taskId) {
    removeInternal(require(sessionId, taskId));
  }

  public synchronized void closeAll() {
    sessions.values().forEach(this::closeQuietly);
    sessions.clear();
  }

  private void purgeExpired() {
    Instant now = clock.instant();
    sessions.values().stream().filter(session -> !session.expiresAt().isAfter(now)).toList()
        .forEach(this::removeInternal);
  }

  private void removeInternal(RegisteredSession session) {
    if (sessions.remove(session.id(), session)) {
      closeQuietly(session);
    }
  }

  private void closeQuietly(RegisteredSession session) {
    try {
      session.closeAction().accept(true);
    } catch (RuntimeException ignored) {
      // Context teardown must not leave the registry entry alive.
    }
  }
}
