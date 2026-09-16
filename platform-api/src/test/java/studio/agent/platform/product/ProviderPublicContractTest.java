package studio.agent.platform.product;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProviderPublicContractTest {
  @Test
  void providerResponsesExposeOnlyCredentialPresence() {
    assertEquals(List.of("id", "name", "providerType", "baseUrl", "credentialConfigured", "defaultProfile"),
        Arrays.stream(ProviderController.ProviderView.class.getRecordComponents()).map(component -> component.getName()).toList());
  }
}
