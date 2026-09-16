package studio.agent.platform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
class SessionAuthenticationFilter extends OncePerRequestFilter {
  private final JdbcClient jdbc;
  SessionAuthenticationFilter(JdbcClient jdbc) { this.jdbc = jdbc; }
  @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
    var header = request.getHeader("Authorization");
    if (header != null && header.startsWith("Bearer ")) {
      var user = jdbc.sql("""
          SELECT u.id,u.organization_id,u.email,u.role FROM auth_tokens t JOIN users u ON u.id=t.user_id
          WHERE t.purpose='SESSION' AND t.token_hash=:hash AND t.used_at IS NULL AND t.expires_at>:now AND u.disabled_at IS NULL
          """).param("hash", TokenDigest.hash(header.substring(7))).param("now", OffsetDateTime.now()).query((rs, n) ->
          new CurrentUser(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4))).optional();
      user.ifPresent(value -> SecurityContextHolder.getContext().setAuthentication(
          new UsernamePasswordAuthenticationToken(value, null, List.of(() -> "ROLE_" + value.role()))));
    }
    chain.doFilter(request, response);
  }
}
