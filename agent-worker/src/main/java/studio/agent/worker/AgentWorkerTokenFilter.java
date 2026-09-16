package studio.agent.worker;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

/** Protects workflow execution with the dedicated Agent Worker token. */
public final class AgentWorkerTokenFilter extends OncePerRequestFilter {
  private final AgentWorkerToken token;
  AgentWorkerTokenFilter(AgentWorkerToken token) { this.token = token; }

  @Override protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().matches("/internal/tasks/[^/]+/execute");
  }

  @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain chain) throws ServletException, IOException {
    try {
      token.require(request.getHeader("Authorization"));
      chain.doFilter(request, response);
    } catch (SecurityException denied) {
      response.setStatus(HttpServletResponse.SC_FORBIDDEN);
      response.setContentType("application/json");
      response.getWriter().write("{\"status\":\"FAILED\",\"failureCode\":\"AGENT_WORKER_UNAUTHORIZED\"}");
    }
  }
}

final class AgentWorkerToken {
  private final byte[] expected;
  AgentWorkerToken(String token) {
    if (token == null || token.isBlank()) throw new IllegalArgumentException("AGENT_WORKER_TOKEN must be configured");
    expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
  }
  void require(String authorization) {
    byte[] actual = authorization == null ? new byte[0] : authorization.getBytes(StandardCharsets.UTF_8);
    if (!MessageDigest.isEqual(expected, actual)) throw new SecurityException("AGENT_WORKER_UNAUTHORIZED");
  }
}

@Configuration
class AgentWorkerSecurityConfiguration {
  @Bean AgentWorkerToken agentWorkerToken(@Value("${AGENT_WORKER_TOKEN}") String token) {
    return new AgentWorkerToken(token);
  }
  @Bean FilterRegistrationBean<AgentWorkerTokenFilter> agentWorkerTokenFilter(AgentWorkerToken token) {
    var registration = new FilterRegistrationBean<>(new AgentWorkerTokenFilter(token));
    registration.addUrlPatterns("/internal/tasks/*");
    registration.setOrder(-100);
    return registration;
  }
}
