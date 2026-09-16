package studio.agent.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("agent-studio")
public record PlatformProperties(String setupToken, String encryptionKey, String agentWorkerToken,
                                 String browserWorkerToken, String agentWorkerBaseUrl, ObjectStore objectStore) {
  public record ObjectStore(String endpoint, String region, String bucket, String accessKey, String secretKey, boolean pathStyle) {}
}
