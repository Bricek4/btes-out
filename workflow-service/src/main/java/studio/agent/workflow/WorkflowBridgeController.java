package studio.agent.workflow;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import studio.agent.contracts.TaskStatus;
import studio.agent.contracts.TaskType;

@RestController
@RequestMapping("/internal/workflows/tasks/{taskId}")
final class WorkflowBridgeController {
  private final WorkflowTaskGateway gateway;

  WorkflowBridgeController(WorkflowTaskGateway gateway) {
    this.gateway = gateway;
  }

  @PostMapping("/start")
  ResponseEntity<StartResponse> start(@PathVariable("taskId") String taskId,
      @RequestBody WorkflowInput input) {
    if (!taskId.equals(input.taskId())) throw new IllegalArgumentException("taskId mismatch");
    WorkflowStartResult result = gateway.start(input);
    StartResponse response = new StartResponse(result.taskId(), result.workflowId(), result.started());
    return ResponseEntity.status(result.started() ? HttpStatus.ACCEPTED : HttpStatus.OK).body(response);
  }

  @GetMapping
  WorkflowStateResponse query(@PathVariable("taskId") String taskId) {
    TaskWorkflowState state = gateway.query(taskId);
    ApprovalResponse approval = state.approvalRequest() == null ? null
        : new ApprovalResponse(state.approvalRequest().type(), state.approvalRequest().markerId(),
            state.approvalRequest().reasonCode(), state.approvalRequest().reference());
    return new WorkflowStateResponse(taskId, state.status(), state.artifactReference(),
        state.failureCode(), state.approvalPending(), state.pauseRequested(),
        state.cancelRequested(), approval);
  }

  @PostMapping("/pause")
  ResponseEntity<SignalResponse> pause(@PathVariable("taskId") String taskId) {
    gateway.pause(taskId);
    return accepted(taskId, "pause");
  }

  @PostMapping("/resume")
  ResponseEntity<SignalResponse> resume(@PathVariable("taskId") String taskId) {
    gateway.resume(taskId);
    return accepted(taskId, "resume");
  }

  @PostMapping("/cancel")
  ResponseEntity<SignalResponse> cancel(@PathVariable("taskId") String taskId) {
    gateway.cancel(taskId);
    return accepted(taskId, "cancel");
  }

  @PostMapping("/approve")
  ResponseEntity<SignalResponse> approve(@PathVariable("taskId") String taskId,
      @RequestBody ApprovalRequest request) {
    ApprovalDecision decision = request.toDecision();
    if (!decision.valid()) throw new IllegalArgumentException("decision is invalid");
    gateway.approve(taskId, decision);
    return accepted(taskId, "approve");
  }

  private static ResponseEntity<SignalResponse> accepted(String taskId, String signal) {
    return ResponseEntity.accepted().body(new SignalResponse(taskId, signal));
  }

  record ApprovalRequest(String decision, String approvedReference) {
    ApprovalDecision toDecision() { return new ApprovalDecision(decision, approvedReference); }
  }

  record StartResponse(String taskId, String workflowId, boolean started) { }
  record SignalResponse(String taskId, String signal) { }
  record ApprovalResponse(String type, String markerId, String reasonCode, String reference) { }
  record WorkflowStateResponse(String taskId, TaskStatus status, String artifactReference,
      String failureCode, boolean approvalPending, boolean pauseRequested, boolean cancelRequested,
      ApprovalResponse approvalRequest) { }
}
