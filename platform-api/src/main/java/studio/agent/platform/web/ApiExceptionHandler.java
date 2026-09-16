package studio.agent.platform.web;

import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
class ApiExceptionHandler {
  @ExceptionHandler(ResponseStatusException.class) ResponseEntity<Map<String,Object>> status(ResponseStatusException e) {
    return ResponseEntity.status(e.getStatusCode()).body(Map.of("code", e.getReason() == null ? "REQUEST_FAILED" : e.getReason()));
  }
  @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class}) ResponseEntity<Map<String,Object>> badRequest(Exception e) {
    return ResponseEntity.badRequest().body(Map.of("code", "INVALID_REQUEST", "message", e.getMessage() == null ? "Invalid request" : e.getMessage()));
  }
  @ExceptionHandler({AccessDeniedException.class}) ResponseEntity<Map<String,Object>> forbidden(Exception e) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("code", "FORBIDDEN"));
  }
  @ExceptionHandler(SecurityException.class) ResponseEntity<Map<String,Object>> workerToken(SecurityException e) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("code", "WORKER_TOKEN_INVALID"));
  }
  @ExceptionHandler(DuplicateKeyException.class) ResponseEntity<Map<String,Object>> conflict(Exception e) {
    return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", "CONFLICT"));
  }
}
