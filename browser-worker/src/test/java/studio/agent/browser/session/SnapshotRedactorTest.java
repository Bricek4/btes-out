package studio.agent.browser.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class SnapshotRedactorTest {
  @Test
  void removesInputValuesAndCredentialStringsFromAriaSnapshot() {
    String raw = """
        - heading \"Account\" [level=1]
        - textbox \"Email\": admin@example.test
        - textbox \"Password\": secret-password
        - combobox \"Region\": Shanghai
        - paragraph: Welcome admin@example.test
        """;

    String redacted = SnapshotRedactor.redact(raw, Set.of("admin@example.test", "secret-password"));

    assertThat(redacted).contains("heading \"Account\"").contains("textbox \"Email\": [REDACTED]");
    assertThat(redacted).doesNotContain("admin@example.test", "secret-password", "Shanghai");
  }
}
