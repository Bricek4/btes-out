package studio.agent.worker;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class AgentWorkerErrorHandlerTest {
  private final AgentWorkerErrorHandler handler = new AgentWorkerErrorHandler();

  @Test void mapsInvalidAndUnexpectedFailuresWithoutEchoingMessages() {
    var invalid = handler.invalidRequest(new IllegalArgumentException("apiKey=must-not-escape"));
    assertEquals(HttpStatus.BAD_REQUEST, invalid.getStatusCode());
    assertEquals("AGENT_REQUEST_INVALID", invalid.getBody().failureCode());
    assertFalse(invalid.getBody().toString().contains("must-not-escape"));

    var internal = handler.internalFailure(new IllegalStateException("prompt=must-not-escape"));
    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, internal.getStatusCode());
    assertEquals("AGENT_INTERNAL_ERROR", internal.getBody().failureCode());
    assertFalse(internal.getBody().toString().contains("must-not-escape"));
  }
}
