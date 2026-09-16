package studio.agent.worker;

import java.net.URI;
import java.util.Map;

/** Task-scoped provider secrets; formatting never reveals the API key. */
public record ProviderConnection(String endpoint, String model, String apiKey,
                                 Map<String, Object> options) {
  public ProviderConnection {
    if (endpoint == null || endpoint.isBlank() || endpoint.length() > 2048) throw new IllegalArgumentException("provider endpoint is invalid");
    URI uri;
    try { uri = URI.create(endpoint); }
    catch (RuntimeException invalid) { throw new IllegalArgumentException("provider endpoint is invalid"); }
    if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
        || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
      throw new IllegalArgumentException("provider endpoint is invalid");
    }
    if (model == null || model.isBlank() || model.length() > 200) throw new IllegalArgumentException("provider model is invalid");
    if (apiKey == null || apiKey.isBlank() || apiKey.length() > 4096) throw new IllegalArgumentException("provider credential is unavailable");
    options = Map.copyOf(options == null ? Map.of() : options);
  }

  @Override public String toString() {
    return "ProviderConnection[endpoint=" + endpoint + ", model=" + model + ", apiKey=[REDACTED], options=" + options.keySet() + "]";
  }
}
