package studio.agent.contracts;

import java.util.Objects;

/** Internal normalized command that binds a public task request to its Idempotency-Key header. */
public record CreateTaskCommand(CreateTaskRequest request, String idempotencyKey) {
  public CreateTaskCommand {
    Objects.requireNonNull(request, "request is required");
    CreateTaskRequest.requireText(idempotencyKey, "idempotencyKey");
    if (idempotencyKey.length() > 255) {
      throw new IllegalArgumentException("idempotencyKey must not exceed 255 characters");
    }
  }
}
