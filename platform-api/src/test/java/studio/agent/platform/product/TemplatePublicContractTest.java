package studio.agent.platform.product;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class TemplatePublicContractTest {
  @Test
  void templateListExposesTheCapabilityTypeForTaskSelection() {
    assertEquals(List.of("id", "name", "skillId", "taskType", "visibility", "latestVersion", "latestVersionId"),
        Arrays.stream(TemplateController.TemplateView.class.getRecordComponents())
            .map(component -> component.getName()).toList());
  }
}
