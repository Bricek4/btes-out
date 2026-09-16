package studio.agent.workflow;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/** Network and persistence work belongs in Activities, outside deterministic workflow code. */
@ActivityInterface
public interface TaskActivities {
  @ActivityMethod(name = "execute-agent-task")
  ActivityOutcome execute(WorkflowInput input);
}
