package studio.agent.browser.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class SessionRegistryTest {
  @Test
  void bindsEveryLookupToTaskAndProfileAndClosesExpiredContexts() {
    var closed = new AtomicBoolean();
    var clock = Clock.fixed(Instant.parse("2026-09-16T00:00:00Z"), ZoneOffset.UTC);
    var registry = new SessionRegistry(2, Duration.ofMinutes(10), clock);
    UUID task = UUID.randomUUID();
    var session = registry.register(task, URI.create("https://example.com"), "admin", closed::set);

    assertThat(registry.require(session.id(), task).profileReference()).isEqualTo("admin");
    assertThatThrownBy(() -> registry.require(session.id(), UUID.randomUUID()))
        .isInstanceOf(SessionAccessException.class).hasMessageContaining("SESSION_TASK_MISMATCH");

    registry.remove(session.id(), task);
    assertThat(closed).isTrue();
  }

  @Test
  void enforcesConcurrentContextLimit() {
    var registry = new SessionRegistry(1, Duration.ofMinutes(10), Clock.systemUTC());
    UUID task = UUID.randomUUID();
    registry.register(task, URI.create("https://example.com"), "admin", ignored -> {});
    assertThatThrownBy(() -> registry.register(task, URI.create("https://example.com"), "member", ignored -> {}))
        .isInstanceOf(SessionAccessException.class).hasMessageContaining("SESSION_LIMIT_REACHED");
  }
}
