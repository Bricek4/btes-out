package studio.agent.platform.project;

import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import studio.agent.platform.importing.ArchiveSafety;
import studio.agent.platform.importing.GitImportService;
import studio.agent.platform.security.CurrentUser;
import studio.agent.platform.storage.ObjectStoreService;

@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {
  private static final long MAX_ARCHIVE=100L*1024*1024; private static final long MAX_EXPANDED=500L*1024*1024;
  private final JdbcClient jdbc;private final ObjectStoreService objects;private final GitImportService git;
  ProjectController(JdbcClient jdbc,ObjectStoreService objects,GitImportService git){this.jdbc=jdbc;this.objects=objects;this.git=git;}
  record CreateProject(String name){} record GitRequest(String url,String branch,String token){}
  record ProjectView(UUID id,String name,UUID ownerId,OffsetDateTime createdAt,boolean shared){}
  record RevisionView(UUID id,int ordinal,String sourceType,String branch,String commit,String sha256,OffsetDateTime createdAt){}

  @PostMapping @ResponseStatus(HttpStatus.CREATED) ProjectView create(CurrentUser user,@RequestBody CreateProject r){var id=UUID.randomUUID();var now=OffsetDateTime.now();if(r.name()==null||r.name().isBlank())throw new IllegalArgumentException("name is required");jdbc.sql("INSERT INTO projects(id,organization_id,owner_id,name,created_at) VALUES(:id,:org,:owner,:name,:now)").param("id",id).param("org",user.organizationId()).param("owner",user.id()).param("name",r.name().trim()).param("now",now).update();return new ProjectView(id,r.name().trim(),user.id(),now,false);}
  @GetMapping List<ProjectView> list(CurrentUser user){return jdbc.sql("SELECT p.id,p.name,p.owner_id,p.created_at,(p.owner_id<>:user) shared FROM projects p JOIN users owner_user ON owner_user.id=p.owner_id WHERE p.organization_id=:org AND owner_user.organization_id=:org AND owner_user.disabled_at IS NULL AND (p.owner_id=:user OR EXISTS(SELECT 1 FROM shares s WHERE s.member_id=:user AND s.owner_id=p.owner_id AND s.resource_type='PROJECT' AND s.resource_id=p.id)) ORDER BY p.created_at DESC").param("user",user.id()).param("org",user.organizationId()).query((rs,n)->new ProjectView(rs.getObject(1,UUID.class),rs.getString(2),rs.getObject(3,UUID.class),rs.getObject(4,OffsetDateTime.class),rs.getBoolean(5))).list();}
  @GetMapping("/{projectId}/revisions") List<RevisionView> revisions(CurrentUser user,@PathVariable UUID projectId){requireRead(user,projectId);return jdbc.sql("SELECT id,ordinal,source_type,source_branch,source_commit,source_sha256,created_at FROM project_revisions WHERE project_id=:id ORDER BY ordinal DESC").param("id",projectId).query((rs,n)->new RevisionView(rs.getObject(1,UUID.class),rs.getInt(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getObject(7,OffsetDateTime.class))).list();}
  @PostMapping("/{projectId}/git/test") Map<String,Object> testGit(CurrentUser user,@PathVariable UUID projectId,@RequestBody GitRequest r){requireOwner(user,projectId);var branches=git.branches(r.url(),r.token());return Map.of("ok",true,"branches",branches);}
  @PostMapping("/{projectId}/git/branches") List<String> branches(CurrentUser user,@PathVariable UUID projectId,@RequestBody GitRequest r){requireOwner(user,projectId);return git.branches(r.url(),r.token());}
  @PostMapping("/{projectId}/imports/git") RevisionView importGit(CurrentUser user,@PathVariable UUID projectId,@RequestBody GitRequest r){requireOwner(user,projectId);var archive=git.archive(r.url(),r.branch(),r.token());return storeRevision(projectId,"GIT",r.url(),r.branch(),archive.commit(),archive.bytes());}
  @PostMapping("/{projectId}/imports/zip") RevisionView importZip(CurrentUser user,@PathVariable UUID projectId,@RequestPart("file") MultipartFile file)throws Exception{requireOwner(user,projectId);if(file.getSize()>MAX_ARCHIVE)throw new IllegalArgumentException("ZIP archive exceeds upload limit");var bytes=file.getBytes();ArchiveSafety.inspect(bytes,20_000,MAX_EXPANDED);return storeRevision(projectId,"ZIP",null,null,null,bytes);}
  @Transactional RevisionView storeRevision(UUID project,String type,String url,String branch,String commit,byte[] bytes){var sha=sha256(bytes);jdbc.sql("SELECT id FROM projects WHERE id=:id FOR UPDATE").param("id",project).query(UUID.class).optional().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"PROJECT_NOT_FOUND"));var ordinal=jdbc.sql("SELECT COALESCE(MAX(ordinal),0)+1 FROM project_revisions WHERE project_id=:id").param("id",project).query(Integer.class).single();var id=UUID.randomUUID();var key="sources/"+project+"/"+id+".zip";objects.put(key,bytes,"application/zip",sha);var now=OffsetDateTime.now();jdbc.sql("INSERT INTO project_revisions(id,project_id,ordinal,source_type,source_url,source_branch,source_commit,source_sha256,object_key,created_at) VALUES(:id,:project,:ordinal,:type,:url,:branch,:commit,:sha,:key,:now)").param("id",id).param("project",project).param("ordinal",ordinal).param("type",type).param("url",url).param("branch",branch).param("commit",commit).param("sha",sha).param("key",key).param("now",now).update();return new RevisionView(id,ordinal,type,branch,commit,sha,now);}
  private void requireOwner(CurrentUser user,UUID project){if(!jdbc.sql("SELECT EXISTS(SELECT 1 FROM projects WHERE id=:id AND owner_id=:owner AND organization_id=:org)").param("id",project).param("owner",user.id()).param("org",user.organizationId()).query(Boolean.class).single())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"PROJECT_NOT_FOUND");}
  private void requireRead(CurrentUser user,UUID project){if(!jdbc.sql("SELECT EXISTS(SELECT 1 FROM projects p JOIN users owner_user ON owner_user.id=p.owner_id WHERE p.id=:id AND p.organization_id=:org AND owner_user.organization_id=:org AND owner_user.disabled_at IS NULL AND (p.owner_id=:user OR EXISTS(SELECT 1 FROM shares s WHERE s.member_id=:user AND s.owner_id=p.owner_id AND s.resource_type='PROJECT' AND s.resource_id=p.id)))").param("id",project).param("user",user.id()).param("org",user.organizationId()).query(Boolean.class).single())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"PROJECT_NOT_FOUND");}
  private static String sha256(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
}
