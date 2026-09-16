package studio.agent.contracts;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** SSE payload; sequence is monotonic per task and enables reconnecting clients to resume. */
public record TaskEvent(UUID taskId, long sequence, TaskStatus status, Instant occurredAt,
                        String resultReference, String failureCode) {
  public TaskEvent {
    Objects.requireNonNull(taskId, "taskId is required");
    if (sequence < 1) throw new IllegalArgumentException("sequence must be positive");
    Objects.requireNonNull(status, "status is required");
    Objects.requireNonNull(occurredAt, "occurredAt is required");
  }
}
