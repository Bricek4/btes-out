package studio.agent.platform.artifact;

import jakarta.servlet.MultipartConfigElement;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ArtifactUploadConfiguration {
  private static final long MAX_FILE_BYTES = 100L * 1024 * 1024;
  private static final long MAX_REQUEST_BYTES = 101L * 1024 * 1024;

  @Bean
  MultipartConfigElement multipartConfig() {
    return new MultipartConfigElement("", MAX_FILE_BYTES, MAX_REQUEST_BYTES, 0);
  }
}
