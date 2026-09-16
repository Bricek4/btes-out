package studio.agent.platform.importing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GitImportServiceTest {
  private final GitImportService.GitPolicy policy = new GitImportService.GitPolicy(
      Set.of("github.com"), 1024, 10, Duration.ofSeconds(5));

  @Test
  void rejects_non_https_private_or_unapproved_git_urls_before_connecting() {
    assertThrows(IllegalArgumentException.class, () -> policy.validate("http://github.com/org/repo.git"));
    assertThrows(IllegalArgumentException.class, () -> policy.validate("https://evil.example/org/repo.git"));
    assertThrows(IllegalArgumentException.class, () -> policy.validate("https://user:secret@github.com/org/repo.git"));
    assertThrows(IllegalArgumentException.class, () -> policy.validate("https://github.com:444/org/repo.git"));
  }

  @Test
  void validates_branch_names_and_allows_default_branch_selection() {
    assertNull(policy.validateBranch(null));
    assertEquals("feature/docs", policy.validateBranch("feature/docs"));
    assertThrows(IllegalArgumentException.class, () -> policy.validateBranch("../escape"));
    assertThrows(IllegalArgumentException.class, () -> policy.validateBranch("-option"));
    assertThrows(IllegalArgumentException.class, () -> policy.validateBranch("feature//docs"));
  }
}
