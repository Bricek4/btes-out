package studio.agent.browser.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.web.filter.OncePerRequestFilter;

public final class WorkerTokenFilter extends OncePerRequestFilter {
  private final byte[] expected;

  public WorkerTokenFilter(String expectedToken) {
    if (expectedToken == null || expectedToken.isBlank()) {
      throw new IllegalArgumentException("AGENT_WORKER_TOKEN must be configured");
    }
    this.expected = expectedToken.getBytes(StandardCharsets.UTF_8);
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    if (!request.getRequestURI().startsWith("/internal/")) {
      filterChain.doFilter(request, response);
      return;
    }
    String authorization = request.getHeader("Authorization");
    byte[] actual = authorization != null && authorization.startsWith("Bearer ")
        ? authorization.substring(7).getBytes(StandardCharsets.UTF_8) : new byte[0];
    if (!MessageDigest.isEqual(expected, actual)) {
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      response.setContentType("application/json");
      response.getWriter().write("{\"status\":\"FAILED\",\"error\":{\"code\":\"UNAUTHORIZED\",\"message\":\"valid worker authentication is required\"}}");
      return;
    }
    filterChain.doFilter(request, response);
  }
}
