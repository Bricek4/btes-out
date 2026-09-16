package studio.agent.browser.session;

import java.util.Set;
import java.util.regex.Pattern;

public final class SnapshotRedactor {
  private static final Pattern INPUT_VALUE = Pattern.compile(
      "(?m)^([ \\t]*-[ \\t]*(?:textbox|searchbox|combobox|spinbutton)[^\\r\\n:]*):.*$");

  private SnapshotRedactor() {}

  public static String redact(String snapshot, Set<String> secrets) {
    String result = snapshot == null ? "" : snapshot;
    for (String secret : secrets) {
      if (secret != null && !secret.isEmpty()) {
        result = result.replace(secret, "[REDACTED]");
      }
    }
    return INPUT_VALUE.matcher(result).replaceAll("$1: [REDACTED]");
  }
}
