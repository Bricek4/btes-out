package studio.agent.platform.product;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProviderResponseParserTest {
  @Test
  void extractsDistinctSortedOpenAiCompatibleModels() {
    var body = """
        {"object":"list","data":[{"id":"z-model"},{"id":"a-model"},{"id":"a-model"},{"ignored":true}]}
        """.getBytes(StandardCharsets.UTF_8);

    assertEquals(List.of("a-model", "z-model"), ProviderResponseParser.parseModels(body));
  }

  @Test
  void rejectsMalformedOrOversizedLists() {
    assertThrows(IllegalArgumentException.class,
        () -> ProviderResponseParser.parseModels("{}".getBytes(StandardCharsets.UTF_8)));
    var items = new StringBuilder("{\"data\":[");
    for (int i = 0; i < 501; i++) items.append(i == 0 ? "" : ",").append("{\"id\":\"m").append(i).append("\"}");
    items.append("]}");
    assertThrows(IllegalArgumentException.class,
        () -> ProviderResponseParser.parseModels(items.toString().getBytes(StandardCharsets.UTF_8)));
  }
}
