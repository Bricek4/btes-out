package studio.agent.platform.product;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Service;

@Service
final class ProviderProbeClient implements AutoCloseable {
  private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
  private final ProviderEndpointPolicy policy;
  private final CloseableHttpClient client;

  ProviderProbeClient(ProviderEndpointPolicy policy) {
    this.policy = policy;
    var connectionConfig = ConnectionConfig.custom().setConnectTimeout(Timeout.ofSeconds(5))
        .setSocketTimeout(Timeout.ofSeconds(10)).build();
    var connections = PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(policy.connectionResolver())
        .setDefaultConnectionConfig(connectionConfig).setMaxConnTotal(20).setMaxConnPerRoute(10).build();
    var requestConfig = RequestConfig.custom().setConnectionRequestTimeout(Timeout.ofSeconds(2))
        .setResponseTimeout(Timeout.ofSeconds(10)).setRedirectsEnabled(false).build();
    this.client = HttpClients.custom().setConnectionManager(connections).setDefaultRequestConfig(requestConfig)
        .disableRedirectHandling().disableAutomaticRetries().build();
  }

  ProbeResult probe(String baseUrl, String apiKey) {
    var request = new HttpGet(policy.modelsEndpointForConnection(baseUrl));
    request.setHeader("Accept", "application/json");
    request.setHeader("Authorization", "Bearer " + apiKey);
    long started = System.nanoTime();
    try {
      return client.execute(request, response -> {
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
        int status = response.getCode();
        if (response.getEntity() == null) return new ProbeResult(false, status, elapsedMillis, List.of(), "EMPTY_RESPONSE");
        try (var input = response.getEntity().getContent()) {
          byte[] body = input.readNBytes(MAX_RESPONSE_BYTES + 1);
          if (body.length > MAX_RESPONSE_BYTES) return new ProbeResult(false, status, elapsedMillis, List.of(), "RESPONSE_TOO_LARGE");
          if (status < 200 || status >= 300) return new ProbeResult(false, status, elapsedMillis, List.of(), "HTTP_" + status);
          return new ProbeResult(true, status, elapsedMillis, ProviderResponseParser.parseModels(body), "OK");
        }
      });
    } catch (IOException | IllegalArgumentException exception) {
      throw new ProviderProbeException("provider connection failed", exception);
    }
  }

  @Override
  @PreDestroy
  public void close() throws IOException {
    client.close();
  }

  record ProbeResult(boolean ok, int statusCode, long latencyMillis, List<String> models, String code) { }

  static final class ProviderProbeException extends RuntimeException {
    ProviderProbeException(String message, Throwable cause) { super(message, cause); }
  }
}
