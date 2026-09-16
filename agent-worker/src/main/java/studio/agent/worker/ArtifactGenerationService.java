package studio.agent.worker;

import java.util.Objects;
import org.springframework.ai.chat.client.ChatClient;
import studio.agent.contracts.TaskType;

/** Model output is kept only long enough to validate and upload an immutable artifact. */
public final class ArtifactGenerationService {
  private final ModelGateway model;
  public ArtifactGenerationService(ModelGateway model) { this.model = Objects.requireNonNull(model); }
  public GeneratedArtifact generate(TaskType type, String sourceEvidence, String template, String changeSummary) {
    if (type != TaskType.PROJECT_DOCS && type != TaskType.USER_GUIDE && type != TaskType.HTML) throw new IllegalArgumentException("unsupported generated artifact type");
    String prompt = "Generate a neutral template-driven " + type + " artifact. Source evidence:\n" + sourceEvidence + "\nTemplate:\n" + template + "\nChanges:\n" + changeSummary;
    String content = model.complete(prompt);
    if (content == null || content.isBlank()) throw new IllegalArgumentException("model returned empty artifact");
    if (type == TaskType.HTML) content = new HtmlRenderer().render(content, "Generated page");
    else ScreenshotMarkers.parseAll(content);
    return new GeneratedArtifact(type == TaskType.HTML ? "site/index.html" : "docs/generated.md", content, changeSummary == null ? "" : changeSummary);
  }
  public interface ModelGateway { String complete(String prompt); }
  public static final class SpringAiGateway implements ModelGateway {
    private final ChatClient client;
    public SpringAiGateway(ChatClient client) { this.client = Objects.requireNonNull(client); }
    @Override public String complete(String prompt) { return client.prompt().user(prompt).call().content(); }
  }
}
record GeneratedArtifact(String path, String content, String sourceChangeSummary) { }
