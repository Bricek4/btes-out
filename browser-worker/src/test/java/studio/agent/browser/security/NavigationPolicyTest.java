package studio.agent.browser.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class NavigationPolicyTest {
  @Test
  void rejectsPrivateAndMetadataAddressesInProduction() throws Exception {
    var policy = new NavigationPolicy(false, List.of(), List.of(), host -> switch (host) {
      case "public.example" -> List.of(InetAddress.getByName("203.0.113.8"));
      case "rebound.example" -> List.of(InetAddress.getByName("10.0.0.7"));
      default -> List.of(InetAddress.getByName(host));
    });

    assertThatThrownBy(() -> policy.validate(URI.create("http://127.0.0.1/admin"), null))
        .isInstanceOf(NavigationRejectedException.class).hasMessageContaining("PRIVATE_ADDRESS_BLOCKED");
    assertThatThrownBy(() -> policy.validate(URI.create("http://169.254.169.254/latest/meta-data"), null))
        .isInstanceOf(NavigationRejectedException.class).hasMessageContaining("PRIVATE_ADDRESS_BLOCKED");
    assertThatThrownBy(() -> policy.validate(URI.create("https://rebound.example/dashboard"), null))
        .isInstanceOf(NavigationRejectedException.class).hasMessageContaining("PRIVATE_ADDRESS_BLOCKED");
  }

  @Test
  void localAllowlistIsExplicitAndStillEnforcesOrigins() throws Exception {
    var policy = new NavigationPolicy(true, List.of("host.docker.internal"), List.of(),
        host -> List.of(InetAddress.getByName("192.168.65.2")));
    URI base = URI.create("http://host.docker.internal:4173");

    policy.validate(URI.create("http://host.docker.internal:4173/admin"), base);
    assertThatThrownBy(() -> policy.validate(URI.create("http://host.docker.internal:5173/admin"), base))
        .isInstanceOf(NavigationRejectedException.class).hasMessageContaining("ORIGIN_NOT_ALLOWED");
    assertThatThrownBy(() -> policy.validate(URI.create("file:///etc/passwd"), base))
        .isInstanceOf(NavigationRejectedException.class).hasMessageContaining("SCHEME_NOT_ALLOWED");
  }
}
