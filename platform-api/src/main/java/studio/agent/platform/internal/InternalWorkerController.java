package studio.agent.platform.internalapi;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;
import studio.agent.platform.security.SecretBox;
import studio.agent.platform.security.WorkerTokenGuard;
import studio.agent.platform.storage.ObjectStoreService;

/** Credentials are exposed only here, after an owner-scoped task join and worker-token check. */
@RestController
@RequestMapping("/internal")
public class InternalWorkerController {
  private final JdbcClient jdbc; private final WorkerTokenGuard tokens; private final SecretBox secrets; private final ObjectStoreService objects; private final ArtifactReservationService artifacts;
  public InternalWorkerController(JdbcClient jdbc,WorkerTokenGuard tokens,SecretBox secrets,ObjectStoreService objects,ArtifactReservationService artifacts){this.jdbc=jdbc;this.tokens=tokens;this.secrets=secrets;this.objects=objects;this.artifacts=artifacts;}
  @GetMapping("/worker-context/agent/{taskId}") ResponseEntity<Map<String,Object>> agent(@PathVariable UUID taskId){var row=task(taskId);var profiles=jdbc.sql("SELECT id,reference,name FROM login_profiles WHERE owner_id=:owner ORDER BY reference").param("owner",row.owner()).query((rs,n)->Map.<String,Object>of("id",rs.getObject(1,UUID.class),"reference",rs.getString(2),"name",rs.getString(3))).list();return noStore(Map.of("taskId",taskId,"baseUrl",row.baseUrl(),"sourceUrl",objects.presignGet(row.sourceKey(),Duration.ofMinutes(10)).toString(),"template",Map.of("id",row.templateId(),"format",row.format(),"markdown",nullToEmpty(row.markdown()),"html",nullToEmpty(row.html()),"css",nullToEmpty(row.css()),"schema",row.schema()),"parameters",row.parameters(),"providerProfileId",row.providerId(),"modelId",row.modelId(),"loginProfiles",profiles));}
  record StatusUpdate(String status,Integer progress,String message,String details,
      String resultReference,String failureCode) {}
  @PostMapping("/tasks/{taskId}/events")
  @org.springframework.transaction.annotation.Transactional
  ResponseEntity<Void> event(@RequestHeader("Authorization") String auth, @PathVariable UUID taskId,
      @RequestBody StatusUpdate update) {
    tokens.requireAgent(auth);
    if (update == null || update.status() == null) throw new IllegalArgumentException("invalid task status");
    studio.agent.contracts.TaskStatus requested;
    try { requested = studio.agent.contracts.TaskStatus.valueOf(update.status()); }
    catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("invalid task status"); }
    if (update.progress() != null && (update.progress() < 0 || update.progress() > 100)) {
      throw new IllegalArgumentException("progress must be between 0 and 100");
    }
    String resultReference = cleanReference(update.resultReference());
    String failureCode = cleanFailureCode(update.failureCode());
    if (requested == studio.agent.contracts.TaskStatus.SUCCEEDED
        && (resultReference == null || failureCode != null)) {
      throw new IllegalArgumentException("successful status requires an artifact reference");
    }
    if (requested == studio.agent.contracts.TaskStatus.FAILED
        && (failureCode == null || resultReference != null)) {
      throw new IllegalArgumentException("failed status requires a failure code");
    }
    if (requested != studio.agent.contracts.TaskStatus.SUCCEEDED
        && requested != studio.agent.contracts.TaskStatus.FAILED
        && requested != studio.agent.contracts.TaskStatus.CANCELED
        && (resultReference != null || failureCode != null)) {
      throw new IllegalArgumentException("terminal details are only valid for terminal statuses");
    }
    String currentValue = jdbc.sql("SELECT status FROM tasks WHERE id=:id AND deleted_at IS NULL FOR UPDATE")
        .param("id", taskId).query(String.class).optional()
        .orElseThrow(() -> new IllegalArgumentException("task not found"));
    studio.agent.contracts.TaskStatus current = studio.agent.contracts.TaskStatus.valueOf(currentValue);
    if (current != requested) {
      try {
        current.transitionTo(requested);
      } catch (IllegalStateException transitionRejected) {
        // A user pause/cancel can race with the final activity response. A worker response may
        // complete that already-running activity, while a terminal task must never be resurrected.
        boolean activityFinishedAfterPause = current == studio.agent.contracts.TaskStatus.PAUSED
            && (requested == studio.agent.contracts.TaskStatus.SUCCEEDED
                || requested == studio.agent.contracts.TaskStatus.FAILED
                || requested == studio.agent.contracts.TaskStatus.CANCELED);
        if (!activityFinishedAfterPause) {
          if (current == studio.agent.contracts.TaskStatus.SUCCEEDED
              || current == studio.agent.contracts.TaskStatus.FAILED
              || current == studio.agent.contracts.TaskStatus.CANCELED) {
            return ResponseEntity.noContent().build();
          }
          throw transitionRejected;
        }
      }
    }
    var now = java.time.OffsetDateTime.now();
    jdbc.sql("UPDATE tasks SET status=:status,updated_at=:now WHERE id=:id")
        .param("status", requested.name()).param("now", now).param("id", taskId).update();
    if (resultReference != null || failureCode != null || requested == studio.agent.contracts.TaskStatus.CANCELED) {
      jdbc.sql("UPDATE tasks SET result_reference=:result,failure_code=:failure,updated_at=:now WHERE id=:id")
          .param("result", resultReference).param("failure", failureCode).param("now", now).param("id", taskId).update();
    }
    var sequence = jdbc.sql("SELECT COALESCE(MAX(sequence),0)+1 FROM task_events WHERE task_id=:id")
        .param("id", taskId).query(Long.class).single();
    String safeDetails = cleanDetails(update.details());
    jdbc.sql("INSERT INTO task_events(task_id,sequence,status,result_reference,failure_code,event_type,progress,message,details,occurred_at) VALUES(:task,:sequence,:status,:result,:failure,'WORKER',:progress,:message,CAST(:details AS jsonb),:now)")
        .param("task", taskId).param("sequence", sequence).param("status", requested.name())
        .param("result", resultReference).param("failure", failureCode).param("progress", update.progress()).param("message", clean(update.message(), 512))
        .param("details", safeDetails).param("now", now).update();
    if (requested == studio.agent.contracts.TaskStatus.WAITING_FOR_APPROVAL) {
      String reference = detailValue(safeDetails, "approvalReference");
      String markerId = detailValue(safeDetails, "markerId");
      String reason = detailValue(safeDetails, "reasonCode");
      String type = detailValue(safeDetails, "approvalType");
      if (reference != null && markerId != null && reason != null && type != null) {
        jdbc.sql("""
            INSERT INTO task_approvals(id,task_id,kind,prompt,choices,requested_at,expires_at,evidence_reference)
            SELECT :id,:task,'TEXT',:prompt,'[]'::jsonb,:now,:expires,:reference
            WHERE NOT EXISTS (SELECT 1 FROM task_approvals WHERE task_id=:task AND decided_at IS NULL AND evidence_reference=:reference)
            """).param("id", UUID.randomUUID()).param("task", taskId)
            .param("prompt", "Confirm screenshot route " + markerId + " (" + reason + ")")
            .param("now", now).param("expires", now.plusHours(24)).param("reference", reference).update();
      }
    }
    return ResponseEntity.noContent().build();
  }
  @GetMapping("/worker-context/browser/{taskId}") ResponseEntity<Map<String,Object>> browser(@RequestHeader("Authorization") String auth,@PathVariable UUID taskId){tokens.requireBrowser(auth);var row=task(taskId);var profiles=jdbc.sql("SELECT id,reference,name,login_url,login_path FROM login_profiles WHERE owner_id=:owner ORDER BY reference").param("owner",row.owner()).query((rs,n)->Map.<String,Object>of("id",rs.getObject(1,UUID.class),"reference",rs.getString(2),"name",rs.getString(3),"loginUrl",rs.getString(4),"loginPath",nullToEmpty(rs.getString(5)))).list();return noStore(Map.of("taskId",taskId,"baseUrl",row.baseUrl(),"allowedOrigins",List.of(origin(row.baseUrl())),"placeholderManifest",List.of(),"loginProfiles",profiles));}
  @PostMapping("/tasks/{taskId}/provider-credential") ResponseEntity<Map<String,Object>> provider(@RequestHeader("Authorization") String auth,@PathVariable UUID taskId){tokens.requireAgent(auth);var row=jdbc.sql("SELECT t.owner_id,p.id,p.base_url,p.encrypted_api_key,p.options::text,t.model_id FROM tasks t JOIN provider_profiles p ON p.id=t.provider_profile_id WHERE t.id=:task AND t.owner_id=p.owner_id").param("task",taskId).query((rs,n)->new Provider(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6))).optional().orElseThrow(()->new IllegalArgumentException("task provider not found"));return noStore(Map.of("providerProfileId",row.id(),"endpoint",row.endpoint(),"model",row.model(),"apiKey",secrets.decrypt(row.owner().toString(),row.key()),"options",row.options()));}
  @PostMapping("/tasks/{taskId}/login-profiles/{profileId}/credential") ResponseEntity<Map<String,Object>> login(@RequestHeader("Authorization") String auth,@PathVariable UUID taskId,@PathVariable UUID profileId){tokens.requireBrowser(auth);var row=jdbc.sql("SELECT p.owner_id,p.reference,p.login_url,p.login_path,p.username_locator::text,p.password_locator::text,p.submit_locator::text,p.encrypted_username,p.encrypted_password FROM tasks t JOIN login_profiles p ON p.owner_id=t.owner_id WHERE t.id=:task AND p.id=:profile").param("task",taskId).param("profile",profileId).query((rs,n)->new Login(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getString(9))).optional().orElseThrow(()->new IllegalArgumentException("login profile not found"));return noStore(Map.of("reference",row.ref(),"loginUrl",row.url(),"loginPath",nullToEmpty(row.path()),"usernameLocator",jsonObject(row.userLocator()),"passwordLocator",jsonObject(row.passwordLocator()),"submitLocator",jsonObject(row.submitLocator()),"username",secrets.decrypt(row.owner().toString(),row.username()),"password",secrets.decrypt(row.owner().toString(),row.password())));}
  record PresignRequest(String name, String kind, String mediaType, long sizeBytes, String sha256,
                        String idempotencyKey) {}

  @PostMapping("/tasks/{taskId}/artifacts/presign")
  ResponseEntity<Map<String,Object>> presign(@RequestHeader("Authorization") String auth,
      @PathVariable UUID taskId, @RequestBody PresignRequest request) {
    return noStore(artifacts.presign(auth, taskId,
        new ArtifactReservationService.PresignRequest(request.name(), request.kind(), request.mediaType(),
            request.sizeBytes(), request.sha256(), request.idempotencyKey())));
  }

  @PostMapping("/tasks/{taskId}/artifacts/{artifactId}/complete")
  ResponseEntity<Map<String,Object>> complete(@RequestHeader("Authorization") String auth,
      @PathVariable UUID taskId, @PathVariable UUID artifactId,
      @RequestBody Map<String,String> request) {
    String reservationId = request == null ? null : request.get("reservationId");
    String manifest = request == null ? null : request.get("manifest");
    return noStore(artifacts.complete(auth, taskId, artifactId,
        new ArtifactReservationService.CompletionRequest(reservationId, manifest)));
  }
  private Task task(UUID id){return jdbc.sql("SELECT t.owner_id,r.object_key,t.template_version_id,v.output_format,v.markdown_template,v.html_template,v.css,v.parameter_schema::text,t.parameters::text,t.provider_profile_id,t.model_id,COALESCE((SELECT login_url FROM login_profiles l WHERE l.owner_id=t.owner_id ORDER BY created_at LIMIT 1),'') FROM tasks t JOIN project_revisions r ON r.id=t.project_revision_id JOIN template_versions v ON v.id=t.template_version_id WHERE t.id=:id").param("id",id).query((rs,n)->new Task(rs.getObject(1,UUID.class),rs.getString(2),rs.getObject(3,UUID.class),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getString(9),rs.getObject(10,UUID.class),rs.getString(11),rs.getString(12))).optional().orElseThrow(()->new IllegalArgumentException("task not found"));}
  private static ResponseEntity<Map<String,Object>> noStore(Map<String,Object> body){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma","no-cache").body(body);}
  private static String nullToEmpty(String v){return v==null?"":v;} private static String origin(String url){try{var u=java.net.URI.create(url);return u.getScheme()+"://"+u.getAuthority();}catch(Exception e){return url;}}
  private static String clean(String value,int limit){if(value==null)return null;var clean=value.replaceAll("[\\r\\n\\t]"," ").replaceAll("(?i)(password|api[_-]?key|token)\\s*[:=]\\s*[^ ]+","[REDACTED]");return clean.length()>limit?clean.substring(0,limit):clean;}
  private static String cleanDetails(String value){
    if(value==null||value.isBlank())return "{}";
    try{
      var node=new tools.jackson.databind.ObjectMapper().readTree(value);
      if(node==null||!node.isObject())return "{}";
      var out=new java.util.LinkedHashMap<String,String>();
      Set<String> allowed=Set.of("approvalType","markerId","reasonCode","approvalReference","stage");
      node.properties().forEach(entry->{if(allowed.contains(entry.getKey())&&entry.getValue()!=null&&entry.getValue().isTextual()){var v=entry.getValue().asString();if(v.length()<=512)out.put(entry.getKey(),v);}});
      return new tools.jackson.databind.ObjectMapper().writeValueAsString(out);
    }catch(Exception ignored){return "{}";}
  }
  private static String cleanReference(String value){if(value==null||value.isBlank())return null;if(value.length()>2048||!value.matches("artifact://[A-Za-z0-9][A-Za-z0-9._/-]*"))throw new IllegalArgumentException("artifact reference is invalid");return value;}
  private static String cleanFailureCode(String value){if(value==null||value.isBlank())return null;if(!value.matches("[A-Z][A-Z0-9_]{0,127}"))throw new IllegalArgumentException("failure code is invalid");return value;}
  private static String detailValue(String json,String key){try{Object value=new tools.jackson.databind.ObjectMapper().readValue(json,Map.class).get(key);return value instanceof String text&& !text.isBlank()?text:null;}catch(Exception ignored){return null;}}
  private static Object jsonObject(String value){try{return new tools.jackson.databind.ObjectMapper().readValue(value,Object.class);}catch(tools.jackson.core.JacksonException e){throw new IllegalStateException("stored locator is invalid",e);}}
  private record Task(UUID owner,String sourceKey,UUID templateId,String format,String markdown,String html,String css,String schema,String parameters,UUID providerId,String modelId,String baseUrl){} private record Provider(UUID owner,UUID id,String endpoint,String key,String options,String model){} private record Login(UUID owner,String ref,String url,String path,String userLocator,String passwordLocator,String submitLocator,String username,String password){} private record Reservation(UUID id,String key,String media,long size,String sha){}
}
