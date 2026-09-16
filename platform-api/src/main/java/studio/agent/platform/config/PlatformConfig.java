package studio.agent.platform.config;

import java.net.URI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import studio.agent.platform.security.SecretBox;
import studio.agent.platform.security.WorkerTokenGuard;

@Configuration
class PlatformConfig {
  @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
  @Bean SecretBox secretBox(PlatformProperties properties) { return new SecretBox(properties.encryptionKey()); }
  @Bean WorkerTokenGuard workerTokenGuard(PlatformProperties properties) { return new WorkerTokenGuard(properties.agentWorkerToken(), properties.browserWorkerToken()); }
  @Bean RestClient agentWorkerClient(PlatformProperties properties) { return RestClient.builder().baseUrl(properties.agentWorkerBaseUrl()).build(); }
  @Bean S3Client s3Client(PlatformProperties properties) {
    var p = properties.objectStore();
    return S3Client.builder().endpointOverride(URI.create(p.endpoint())).region(Region.of(p.region()))
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(p.accessKey(), p.secretKey())))
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(p.pathStyle()).build()).build();
  }
  @Bean S3Presigner s3Presigner(PlatformProperties properties) {
    var p = properties.objectStore();
    return S3Presigner.builder().endpointOverride(URI.create(p.endpoint())).region(Region.of(p.region()))
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(p.accessKey(), p.secretKey())))
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(p.pathStyle()).build()).build();
  }
}
