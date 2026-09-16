package studio.agent.browser.session;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Internal, token-filtered session API consumed by AgentWorker declarative tools. */
@RestController
@RequestMapping("/internal/sessions")
public final class BrowserSessionController {
  private final PlaywrightBrowserService service;
  public BrowserSessionController(PlaywrightBrowserService service) { this.service = service; }
  @PostMapping public OpenSessionResult open(@jakarta.validation.Valid @RequestBody OpenRequest r) { return service.open(new OpenSessionCommand(r.taskId(), URI.create(r.baseUrl()), r.loginProfileReference())); }
  @PostMapping("/{id}/login") public OperationResult login(@PathVariable UUID id, @jakarta.validation.Valid @RequestBody TaskRequest r) { return service.login(id, r.taskId()); }
  @GetMapping("/{id}/snapshot") public OperationResult snapshot(@PathVariable UUID id, @org.springframework.web.bind.annotation.RequestParam UUID taskId) { return service.snapshot(id, taskId); }
  @PostMapping("/{id}/navigate") public OperationResult navigate(@PathVariable UUID id,@RequestBody NavigateRequest r){return service.navigate(id,new NavigateCommand(r.taskId(),r.target(),r.expected()));}
  @PostMapping("/{id}/click") public OperationResult click(@PathVariable UUID id,@RequestBody LocatorRequest r){return service.click(id,new ClickCommand(r.taskId(),r.locator(),r.expected()));}
  @PostMapping("/{id}/fill") public OperationResult fill(@PathVariable UUID id,@RequestBody ValueRequest r){return service.fill(id,new FillCommand(r.taskId(),r.locator(),r.value(),r.expected()));}
  @PostMapping("/{id}/select") public OperationResult select(@PathVariable UUID id,@RequestBody ValueRequest r){return service.select(id,new SelectCommand(r.taskId(),r.locator(),r.value(),r.expected()));}
  @PostMapping("/{id}/check") public OperationResult check(@PathVariable UUID id,@RequestBody LocatorRequest r){return service.check(id,new CheckCommand(r.taskId(),r.locator(),r.expected()));}
  @PostMapping("/{id}/uncheck") public OperationResult uncheck(@PathVariable UUID id,@RequestBody LocatorRequest r){return service.uncheck(id,new CheckCommand(r.taskId(),r.locator(),r.expected()));}
  @PostMapping("/{id}/wait") public OperationResult waitFor(@PathVariable UUID id,@RequestBody LocatorRequest r){return service.waitFor(id,new WaitCommand(r.taskId(),r.locator(),r.expected()));}
  @PostMapping("/{id}/screenshot") public ScreenshotResult screenshot(@PathVariable UUID id,@RequestBody ScreenshotRequest r){return service.screenshot(id,new ScreenshotCommand(r.taskId(),r.markerId(),r.caption(),r.filename(),r.expected()));}
  @DeleteMapping("/{id}") public ResponseEntity<Void> close(@PathVariable UUID id,@org.springframework.web.bind.annotation.RequestParam UUID taskId){service.close(id,taskId);return ResponseEntity.noContent().build();}
  record TaskRequest(@NotNull UUID taskId){}
  record OpenRequest(@NotNull UUID taskId,@NotBlank String baseUrl,@NotBlank String loginProfileReference){}
  record NavigateRequest(@NotNull UUID taskId,@NotBlank String target,ExpectedState expected){}
  record LocatorRequest(@NotNull UUID taskId,@NotNull LocatorSpec locator,ExpectedState expected){}
  record ValueRequest(@NotNull UUID taskId,@NotNull LocatorSpec locator,@NotBlank String value,ExpectedState expected){}
  record ScreenshotRequest(@NotNull UUID taskId,@NotBlank String markerId,String caption,@NotBlank String filename,ExpectedState expected){}
}
