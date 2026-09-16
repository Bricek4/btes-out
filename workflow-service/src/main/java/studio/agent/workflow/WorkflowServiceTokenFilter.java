package studio.agent.workflow;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

final class WorkflowServiceTokenFilter extends OncePerRequestFilter {
  private static final int MAX_JSON_BYTES = 16_384;
  private final byte[] expected;

  WorkflowServiceTokenFilter(String token) {
    if (token == null || token.isBlank() || token.length() < 32) {
      throw new IllegalArgumentException("WORKFLOW_SERVICE_TOKEN must contain at least 32 characters");
    }
    expected = token.getBytes(StandardCharsets.UTF_8);
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith("/internal/");
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain chain) throws ServletException, IOException {
    String supplied = request.getHeader("Authorization");
    if (supplied == null || !supplied.startsWith("Bearer ")
        || !MessageDigest.isEqual(expected,
            supplied.substring("Bearer ".length()).getBytes(StandardCharsets.UTF_8))) {
      writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED");
      return;
    }
    long length = request.getContentLengthLong();
    if (length > MAX_JSON_BYTES) {
      writeError(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "REQUEST_TOO_LARGE");
      return;
    }
    if (request.getContentType() != null
        && request.getContentType().startsWith(MediaType.APPLICATION_JSON_VALUE)
        && length < 0) {
      writeError(response, HttpServletResponse.SC_LENGTH_REQUIRED, "LENGTH_REQUIRED");
      return;
    }
    chain.doFilter(request, response);
  }

  private static void writeError(HttpServletResponse response, int status, String code)
      throws IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.getWriter().write("{\"code\":\"" + code + "\"}");
  }
}
