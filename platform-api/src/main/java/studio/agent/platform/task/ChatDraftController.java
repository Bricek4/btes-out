package studio.agent.platform.task;

import java.util.Map;
import org.springframework.web.bind.annotation.*;
import studio.agent.platform.security.CurrentUser;

/** Deliberately transient: input text is classified and immediately discarded. */
@RestController
@RequestMapping("/api/v1/chat")
public class ChatDraftController {
  @PostMapping("/parse") Map<String,Object> parse(CurrentUser user,@RequestBody Map<String,String> request){var text=request.get("text");if(text==null||text.isBlank())throw new IllegalArgumentException("text is required");var lower=text.toLowerCase(java.util.Locale.ROOT);var type=lower.contains("screenshot")?"SCREENSHOT":lower.contains("html")?"HTML":lower.contains("guide")?"USER_GUIDE":"PROJECT_DOCS";return Map.of("type",type,"parameters",Map.of("title",text.length()>120?text.substring(0,120):text),"transient",true);}
}
