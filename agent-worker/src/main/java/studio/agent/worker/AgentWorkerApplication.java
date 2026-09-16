package studio.agent.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(excludeName = {
    "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration",
    "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration",
    "org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration",
    "org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration",
    "org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration",
    "org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration"
})
public class AgentWorkerApplication {
  public static void main(String[] args) { SpringApplication.run(AgentWorkerApplication.class, args); }
}
