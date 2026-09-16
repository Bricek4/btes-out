package studio.agent.workflow;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
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
      // Chunked transfer encoding is valid for internal clients such as RestClient. Buffer the
      // bounded body so the size guard remains effective without requiring Content-Length.
      byte[] body = request.getInputStream().readNBytes(MAX_JSON_BYTES + 1);
      if (body.length > MAX_JSON_BYTES) {
        writeError(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "REQUEST_TOO_LARGE");
        return;
      }
      chain.doFilter(new CachedBodyRequest(request, body), response);
      return;
    }
    chain.doFilter(request, response);
  }

  private static final class CachedBodyRequest extends HttpServletRequestWrapper {
    private final byte[] body;

    private CachedBodyRequest(HttpServletRequest request, byte[] body) {
      super(request);
      this.body = body;
    }

    @Override public int getContentLength() { return body.length; }
    @Override public long getContentLengthLong() { return body.length; }

    @Override public ServletInputStream getInputStream() {
      ByteArrayInputStream input = new ByteArrayInputStream(body);
      return new ServletInputStream() {
        @Override public int read() { return input.read(); }
        @Override public int read(byte[] bytes, int offset, int length) {
          return input.read(bytes, offset, length);
        }
        @Override public boolean isFinished() { return input.available() == 0; }
        @Override public boolean isReady() { return true; }
        @Override public void setReadListener(ReadListener listener) { }
      };
    }

    @Override public BufferedReader getReader() {
      return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }
  }

  private static void writeError(HttpServletResponse response, int status, String code)
      throws IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.getWriter().write("{\"code\":\"" + code + "\"}");
  }
}
