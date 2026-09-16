package studio.agent.workflow;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
final class WorkflowApiExceptionHandler {
  @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
  ResponseEntity<ApiError> invalidRequest() {
    return ResponseEntity.badRequest().body(new ApiError("INVALID_REQUEST"));
  }

  @ExceptionHandler(WorkflowTaskNotFoundException.class)
  ResponseEntity<ApiError> notFound() {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError("WORKFLOW_NOT_FOUND"));
  }

  @ExceptionHandler(WorkflowStartConflictException.class)
  ResponseEntity<ApiError> conflict() {
    return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError("WORKFLOW_INPUT_CONFLICT"));
  }

  @ExceptionHandler(WorkflowBridgeUnavailableException.class)
  ResponseEntity<ApiError> unavailable() {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(new ApiError("WORKFLOW_SERVICE_UNAVAILABLE"));
  }

  record ApiError(String code) { }
}
