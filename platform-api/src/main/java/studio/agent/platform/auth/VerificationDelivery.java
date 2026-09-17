package studio.agent.platform.auth;

import java.net.URI;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Sends one-time auth links without writing tokens to logs or application storage. */
@Component
final class VerificationDelivery {
  private final ObjectProvider<JavaMailSender> sender;
  private final URI publicBaseUrl;
  private final String from;

  VerificationDelivery(ObjectProvider<JavaMailSender> sender,
      @Value("${agent-studio.public-base-url:http://localhost:8088}") String publicBaseUrl,
      @Value("${agent-studio.mail-from:no-reply@agent-studio.local}") String from) {
    this.sender = Objects.requireNonNull(sender, "mail sender is required");
    try {
      this.publicBaseUrl = URI.create(publicBaseUrl.trim());
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("PUBLIC_BASE_URL is invalid");
    }
    if (!("http".equalsIgnoreCase(this.publicBaseUrl.getScheme())
        || "https".equalsIgnoreCase(this.publicBaseUrl.getScheme()))
        || this.publicBaseUrl.getHost() == null) {
      throw new IllegalArgumentException("PUBLIC_BASE_URL must be an HTTP(S) URL");
    }
    if (from == null || from.isBlank() || from.length() > 320) {
      throw new IllegalArgumentException("MAIL_FROM is invalid");
    }
    this.from = from.trim();
  }

  void sendVerification(String email, String token) {
    send(email, "Verify your Sourcewright account", "/verify-email?token=" + encode(token),
        "Use this link to verify your account. It expires in 24 hours.");
  }

  void sendPasswordReset(String email, String token) {
    send(email, "Reset your Sourcewright password", "/reset-password?token=" + encode(token),
        "Use this link to choose a new password. It expires in 1 hour.");
  }

  private void send(String email, String subject, String path, String explanation) {
    JavaMailSender mail = sender.getIfAvailable();
    if (mail == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "EMAIL_DELIVERY_UNAVAILABLE");
    URI link = publicBaseUrl.resolve(path);
    var message = new SimpleMailMessage();
    message.setFrom(from);
    message.setTo(email);
    message.setSubject(subject);
    message.setText(explanation + "\n\n" + link);
    try {
      mail.send(message);
    } catch (RuntimeException failure) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "EMAIL_DELIVERY_UNAVAILABLE");
    }
  }

  private static String encode(String value) {
    return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
  }
}
