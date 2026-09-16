package studio.agent.platform.task;
import java.time.OffsetDateTime;
import java.util.List;
final class ApprovalRules {
  private ApprovalRules() {}
  static void request(String status,String kind,String prompt,List<String> choices,OffsetDateTime expiry,OffsetDateTime now){if(!"RUNNING".equals(status))throw new IllegalStateException("approval requires a running task");if(prompt==null||prompt.isBlank())throw new IllegalArgumentException("approval prompt is required");if(!"CHOICE".equals(kind)&&!"TEXT".equals(kind))throw new IllegalArgumentException("approval kind is invalid");if(expiry!=null&&!expiry.isAfter(now))throw new IllegalArgumentException("approval expiry must be in the future");if("CHOICE".equals(kind)&&(choices==null||choices.isEmpty()||choices.stream().anyMatch(v->v==null||v.isBlank())))throw new IllegalArgumentException("choice approval requires choices");}
  static void decide(String kind,List<String> choices,String decision,String text,OffsetDateTime expiry,OffsetDateTime now){if(expiry!=null&&!expiry.isAfter(now))throw new IllegalStateException("approval expired");if("CHOICE".equals(kind)&&!choices.contains(decision))throw new IllegalArgumentException("decision is not an offered choice");if("TEXT".equals(kind)&&(text==null||text.isBlank()))throw new IllegalArgumentException("text approval requires a response");}
}
