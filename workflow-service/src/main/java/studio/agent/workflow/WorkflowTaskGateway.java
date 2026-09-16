package studio.agent.workflow;

interface WorkflowTaskGateway {
  WorkflowStartResult start(WorkflowInput input);
  TaskWorkflowState query(String taskId);
  void pause(String taskId);
  void resume(String taskId);
  void cancel(String taskId);
  void approve(String taskId, ApprovalDecision decision);
}

record WorkflowStartResult(String taskId, String workflowId, boolean started) { }
