package studio.agent.platform.product;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;
import studio.agent.platform.security.CurrentUser;
import studio.agent.platform.security.SecretBox;

/** Owner-scoped provider configuration. Responses deliberately contain only a masked key. */
@RestController
@RequestMapping("/api/v1/providers")
public class ProviderController {
  private final JdbcClient jdbc; private final SecretBox secrets;
  public ProviderController(JdbcClient jdbc, SecretBox secrets) { this.jdbc=jdbc; this.secrets=secrets; }
  record ProviderInput(String name,String providerType,String baseUrl,String apiKey,Map<String,Object> options,Boolean defaultProfile) {}
  record ModelInput(String modelId,String displayName,Map<String,Object> capabilities,Boolean defaultModel) {}
  record ProviderView(UUID id,String name,String providerType,String baseUrl,String maskedApiKey,boolean defaultProfile) {}
  record ModelView(String modelId,String displayName,String capabilities,boolean defaultModel) {}

  @GetMapping List<ProviderView> list(CurrentUser user) { return jdbc.sql("SELECT id,name,provider_type,base_url,encrypted_api_key,is_default FROM provider_profiles WHERE owner_id=:owner ORDER BY created_at DESC").param("owner",user.id()).query((rs,n)->new ProviderView(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),SecretBox.mask(secrets.decrypt(user.id().toString(),rs.getString(5))),rs.getBoolean(6))).list(); }
  @PostMapping @ResponseStatus(HttpStatus.CREATED) ProviderView create(CurrentUser user,@RequestBody ProviderInput r) { required(r.name(),"name"); required(r.providerType(),"providerType"); required(r.baseUrl(),"baseUrl"); required(r.apiKey(),"apiKey"); var id=UUID.randomUUID();var now=OffsetDateTime.now();boolean selected=Boolean.TRUE.equals(r.defaultProfile());if(selected)jdbc.sql("UPDATE provider_profiles SET is_default=false WHERE owner_id=:owner").param("owner",user.id()).update();jdbc.sql("INSERT INTO provider_profiles(id,owner_id,name,provider_type,base_url,encrypted_api_key,options,is_default,created_at,updated_at) VALUES(:id,:owner,:name,:type,:url,:key,CAST(:options AS jsonb),:default,:now,:now)").param("id",id).param("owner",user.id()).param("name",r.name().trim()).param("type",r.providerType().trim()).param("url",r.baseUrl().trim()).param("key",secrets.encrypt(user.id().toString(),r.apiKey())).param("options",json(r.options())).param("default",selected).param("now",now).update();return new ProviderView(id,r.name().trim(),r.providerType().trim(),r.baseUrl().trim(),SecretBox.mask(r.apiKey()),selected); }
  @PostMapping("/{providerId}/models") @ResponseStatus(HttpStatus.CREATED) ModelView addModel(CurrentUser user,@PathVariable UUID providerId,@RequestBody ModelInput r) { required(r.modelId(),"modelId"); required(r.displayName(),"displayName"); requireOwner(user,providerId);boolean selected=Boolean.TRUE.equals(r.defaultModel());if(selected)jdbc.sql("UPDATE provider_models SET is_default=false WHERE provider_profile_id=:id").param("id",providerId).update();jdbc.sql("INSERT INTO provider_models(provider_profile_id,model_id,display_name,capabilities,is_default) VALUES(:id,:model,:name,CAST(:caps AS jsonb),:default)").param("id",providerId).param("model",r.modelId().trim()).param("name",r.displayName().trim()).param("caps",json(r.capabilities())).param("default",selected).update();return new ModelView(r.modelId().trim(),r.displayName().trim(),json(r.capabilities()),selected); }
  @GetMapping("/{providerId}/models") List<ModelView> models(CurrentUser user,@PathVariable UUID providerId) { requireOwner(user,providerId);return jdbc.sql("SELECT model_id,display_name,capabilities::text,is_default FROM provider_models WHERE provider_profile_id=:id ORDER BY model_id").param("id",providerId).query((rs,n)->new ModelView(rs.getString(1),rs.getString(2),rs.getString(3),rs.getBoolean(4))).list(); }
  private void requireOwner(CurrentUser user,UUID id){if(!jdbc.sql("SELECT EXISTS(SELECT 1 FROM provider_profiles WHERE id=:id AND owner_id=:owner)").param("id",id).param("owner",user.id()).query(Boolean.class).single())throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND,"PROVIDER_NOT_FOUND");}
  private static void required(String value,String name){if(value==null||value.isBlank())throw new IllegalArgumentException(name+" is required");}
  private static String json(Map<String,Object> value){return value==null?"{}":new tools.jackson.databind.ObjectMapper().writeValueAsString(value);}
}
