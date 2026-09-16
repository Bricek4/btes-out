package studio.agent.worker;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Maps request and unexpected failures to stable codes without exposing messages or bodies. */
@RestControllerAdvice
public final class AgentWorkerErrorHandler {
  @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class,
      MethodArgumentTypeMismatchException.class})
  ResponseEntity<ErrorResponse> invalidRequest(Exception ignored) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(new ErrorResponse("FAILED", "AGENT_REQUEST_INVALID"));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ErrorResponse> internalFailure(Exception ignored) {
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(new ErrorResponse("FAILED", "AGENT_INTERNAL_ERROR"));
  }

  record ErrorResponse(String status, String failureCode) { }
}
