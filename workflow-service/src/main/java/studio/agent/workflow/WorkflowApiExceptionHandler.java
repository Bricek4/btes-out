package studio.agent.workflow;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestControllerAdvice
final class WorkflowApiExceptionHandler {
  private static final Logger LOG = LoggerFactory.getLogger(WorkflowApiExceptionHandler.class);

  @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
  ResponseEntity<ApiError> invalidRequest(Exception failure, HttpServletRequest request) {
    // Keep diagnostics to types, lengths and a body hash. Jackson messages can contain request data.
    Object bytes = request.getAttribute(WorkflowServiceTokenFilter.BUFFERED_BODY_BYTES);
    Object hash = request.getAttribute(WorkflowServiceTokenFilter.BUFFERED_BODY_SHA256);
    String cause = failure.getCause() == null ? "none" : failure.getCause().getClass().getSimpleName();
    LOG.warn("workflow request rejected as invalid: type={}, cause={}, contentType={}, contentLength={}, bufferedBytes={}, bodyHash={}",
        failure.getClass().getSimpleName(), cause, request.getContentType(), request.getContentLengthLong(), bytes,
        hash == null ? "none" : String.valueOf(hash).substring(0, Math.min(12, String.valueOf(hash).length())));
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
