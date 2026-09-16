package studio.agent.platform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/** Enforces worker scopes before any internal controller can read a credential or reservation. */
public final class InternalWorkerTokenFilter extends OncePerRequestFilter {
  private final WorkerTokenGuard guard;
  public InternalWorkerTokenFilter(WorkerTokenGuard guard) { this.guard=guard; }
  @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !request.getRequestURI().startsWith("/internal/"); }
  @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
    try {
      var scope=InternalWorkerScope.forPath(request.getRequestURI(), null);
      if (scope==InternalWorkerScope.AGENT) guard.requireAgent(request.getHeader("Authorization"));
      else if(scope==InternalWorkerScope.BROWSER) guard.requireBrowser(request.getHeader("Authorization"));
      else { try { guard.requireAgent(request.getHeader("Authorization")); } catch(SecurityException denied){ guard.requireBrowser(request.getHeader("Authorization")); } }
      chain.doFilter(request,response);
    } catch (SecurityException e) { response.sendError(HttpServletResponse.SC_FORBIDDEN,"WORKER_TOKEN_INVALID"); }
  }
}
