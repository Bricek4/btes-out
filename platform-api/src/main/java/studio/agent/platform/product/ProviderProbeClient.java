package studio.agent.platform.product;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
final class ProviderProbeClient {
  private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
  private final ProviderEndpointPolicy policy;
  private final HttpClient client;

  ProviderProbeClient() {
    this(new ProviderEndpointPolicy(), HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER).build());
  }

  ProviderProbeClient(ProviderEndpointPolicy policy, HttpClient client) {
    this.policy = policy;
    this.client = client;
  }

  ProbeResult probe(String baseUrl, String apiKey) {
    var request = HttpRequest.newBuilder(policy.modelsEndpoint(baseUrl)).timeout(Duration.ofSeconds(10))
        .header("Accept", "application/json").header("Authorization", "Bearer " + apiKey).GET().build();
    long started = System.nanoTime();
    try {
      var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
      long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
      try (var input = response.body()) {
        byte[] body = input.readNBytes(MAX_RESPONSE_BYTES + 1);
        if (body.length > MAX_RESPONSE_BYTES) return new ProbeResult(false, response.statusCode(), elapsedMillis, List.of(), "RESPONSE_TOO_LARGE");
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
          return new ProbeResult(false, response.statusCode(), elapsedMillis, List.of(), "HTTP_" + response.statusCode());
        }
        return new ProbeResult(true, response.statusCode(), elapsedMillis, ProviderResponseParser.parseModels(body), "OK");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new ProviderProbeException("provider request interrupted", exception);
    } catch (IOException | IllegalArgumentException exception) {
      throw new ProviderProbeException("provider connection failed", exception);
    }
  }

  record ProbeResult(boolean ok, int statusCode, long latencyMillis, List<String> models, String code) { }

  static final class ProviderProbeException extends RuntimeException {
    ProviderProbeException(String message, Throwable cause) { super(message, cause); }
  }
}
