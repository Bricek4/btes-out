package studio.agent.worker;

import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The request body is deliberately not persisted or logged. */
@RestController
public final class ChatParseController {
  private final ChatDraftParser parser = new ChatDraftParser();
  @PostMapping("/internal/chat/parse")
  ResponseEntity<TaskDraft> parse(@RequestBody ChatParseRequest request) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(parser.parse(request.message()));
  }
  public record ChatParseRequest(String message, Map<String, String> providerConfiguration) { }
}
