package studio.agent.worker;

import java.util.Map;
import java.util.Objects;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

/** Spring AI OpenAI-compatible gateway, created per task and with prompt observations disabled. */
public final class SpringAiModelGateway implements ArtifactGenerationService.ModelGateway {
  private final ChatModel model;

  public SpringAiModelGateway(ProviderConnection provider) {
    Objects.requireNonNull(provider, "provider is required");
    Map<String, Object> extraBody = Map.of();
    Object thinking = provider.options().get("enable_thinking");
    if (thinking instanceof Boolean enableThinking) {
      extraBody = Map.of("enable_thinking", enableThinking);
    }
    OpenAiChatOptions options = OpenAiChatOptions.builder()
        .baseUrl(provider.endpoint())
        .apiKey(provider.apiKey())
        .model(provider.model())
        .maxRetries(0)
        .store(false)
        .extraBody(extraBody)
        .build();
    this.model = OpenAiChatModel.builder()
        .options(options)
        .observationRegistry(ObservationRegistry.NOOP)
        .build();
  }

  @Override public String complete(String prompt) {
    if (prompt == null || prompt.isBlank()) throw new IllegalArgumentException("model request is empty");
    try {
      ChatResponse response = model.call(new Prompt(new UserMessage(prompt)));
      if (response == null || response.getResult() == null || response.getResult().getOutput() == null) return null;
      return response.getResult().getOutput().getText();
    } catch (RuntimeException providerFailure) {
      // SDK exception bodies can echo request details. Keep the failure code only.
      throw new ModelCallFailure("MODEL_REQUEST_FAILED");
    }
  }

  static final class ModelCallFailure extends IllegalStateException {
    ModelCallFailure(String code) { super(code); }
  }
}
