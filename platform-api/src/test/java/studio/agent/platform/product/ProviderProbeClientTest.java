package studio.agent.platform.product;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ProviderProbeClientTest {
  @Test
  void connectionResolverRejectsPrivateAddressAtConnectTime() throws Exception {
    var calls = new AtomicInteger();
    var policy = new ProviderEndpointPolicy(host -> new InetAddress[] {InetAddress.getByName(
        calls.incrementAndGet() == 1 ? "1.1.1.1" : "127.0.0.1")});
    policy.modelsEndpoint("https://rebind.invalid/v1");
    try (var client = new ProviderProbeClient(policy)) {
      assertThrows(ProviderProbeClient.ProviderProbeException.class,
          () -> client.probe("https://rebind.invalid/v1", "dummy-credential"));
    }
    org.junit.jupiter.api.Assertions.assertTrue(calls.get() >= 2);
  }
}
