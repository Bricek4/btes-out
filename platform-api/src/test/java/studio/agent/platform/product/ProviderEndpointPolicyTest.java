package studio.agent.platform.product;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProviderEndpointPolicyTest {
  @Test
  void acceptsPublicHttpsAndNormalizesModelsEndpoint() throws Exception {
    var policy = new ProviderEndpointPolicy(host -> new InetAddress[] {InetAddress.getByName("1.1.1.1")});
    assertEquals(URI.create("https://api.example.com/v1/models"), policy.modelsEndpoint("https://api.example.com/v1/"));
  }

  @Test
  void rejectsCredentialsHttpAndPrivateResolution() throws Exception {
    var privatePolicy = new ProviderEndpointPolicy(host -> new InetAddress[] {InetAddress.getByName("127.0.0.1")});
    assertThrows(IllegalArgumentException.class, () -> privatePolicy.modelsEndpoint("https://api.example.com/v1"));

    var publicPolicy = new ProviderEndpointPolicy(host -> new InetAddress[] {InetAddress.getByName("1.1.1.1")});
    assertThrows(IllegalArgumentException.class, () -> publicPolicy.modelsEndpoint("http://api.example.com/v1"));
    assertThrows(IllegalArgumentException.class, () -> publicPolicy.modelsEndpoint("https://user:pass@api.example.com/v1"));
  }

  @Test
  void rejectsCarrierGradeNatDocumentationAndUniqueLocalRanges() throws Exception {
    for (String address : List.of("100.64.0.1", "203.0.113.10", "fc00::1", "2001:db8::1", "64:ff9b::7f00:1")) {
      var policy = new ProviderEndpointPolicy(host -> new InetAddress[] {InetAddress.getByName(address)});
      assertThrows(IllegalArgumentException.class, () -> policy.modelsEndpoint("https://api.example.com/v1"));
    }
  }
}
