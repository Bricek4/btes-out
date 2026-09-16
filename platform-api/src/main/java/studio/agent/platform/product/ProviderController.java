package studio.agent.platform.product;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import studio.agent.platform.security.CurrentUser;
import studio.agent.platform.security.SecretBox;

/** Owner-scoped provider configuration. API keys never appear in public responses. */
@RestController
@RequestMapping("/api/v1/providers")
public class ProviderController {
  private final JdbcClient jdbc;
  private final SecretBox secrets;
  private final ProviderProbeClient probes;
  private final ProviderEndpointPolicy endpoints;

  public ProviderController(JdbcClient jdbc, SecretBox secrets, ProviderProbeClient probes, ProviderEndpointPolicy endpoints) {
    this.jdbc = jdbc;
    this.secrets = secrets;
    this.probes = probes;
    this.endpoints = endpoints;
  }

  record ProviderInput(String name, String providerType, String baseUrl, String apiKey,
                       Map<String, Object> options, Boolean defaultProfile) { }
  record ProviderUpdate(String name, String providerType, String baseUrl,
                        Map<String, Object> options, Boolean defaultProfile) { }
  record CredentialInput(String apiKey) { }
  record ModelInput(String modelId, String displayName, Map<String, Object> capabilities, Boolean defaultModel) { }
  record ProviderView(UUID id, String name, String providerType, String baseUrl,
                      boolean credentialConfigured, boolean defaultProfile) { }
  record ModelView(String modelId, String displayName, String capabilities, boolean defaultModel) { }
  record ProbeView(boolean ok, int statusCode, long latencyMillis, int modelCount, String code) { }
  record DiscoveredModel(String modelId, String displayName) { }

  @GetMapping
  List<ProviderView> list(CurrentUser user) {
    return jdbc.sql("SELECT id,name,provider_type,base_url,is_default FROM provider_profiles WHERE owner_id=:owner ORDER BY created_at DESC")
        .param("owner", user.id()).query((rs, row) -> new ProviderView(rs.getObject(1, UUID.class),
            rs.getString(2), rs.getString(3), rs.getString(4), true, rs.getBoolean(5))).list();
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Transactional
  ProviderView create(CurrentUser user, @RequestBody ProviderInput request) {
    String name = required(request.name(), "name", 255);
    String type = required(request.providerType(), "providerType", 64);
    String baseUrl = checkedBaseUrl(request.baseUrl());
    String apiKey = credential(request.apiKey());
    var id = UUID.randomUUID();
    var now = OffsetDateTime.now();
    boolean selected = Boolean.TRUE.equals(request.defaultProfile());
    if (selected) clearDefault(user.id());
    jdbc.sql("""
        INSERT INTO provider_profiles(id,owner_id,name,provider_type,base_url,encrypted_api_key,options,is_default,created_at,updated_at)
        VALUES(:id,:owner,:name,:type,:url,:key,CAST(:options AS jsonb),:default,:now,:now)
        """).param("id", id).param("owner", user.id()).param("name", name).param("type", type)
        .param("url", baseUrl).param("key", secrets.encrypt(user.id().toString(), apiKey))
        .param("options", json(request.options())).param("default", selected).param("now", now).update();
    return new ProviderView(id, name, type, baseUrl, true, selected);
  }

  @PatchMapping("/{providerId}")
  @Transactional
  ProviderView update(CurrentUser user, @PathVariable UUID providerId, @RequestBody ProviderUpdate request) {
    var current = owned(user, providerId);
    String name = request.name() == null ? current.name() : required(request.name(), "name", 255);
    String type = request.providerType() == null ? current.providerType() : required(request.providerType(), "providerType", 64);
    String baseUrl = request.baseUrl() == null ? current.baseUrl() : checkedBaseUrl(request.baseUrl());
    boolean selected = request.defaultProfile() == null ? current.defaultProfile() : request.defaultProfile();
    String options = request.options() == null ? current.options() : json(request.options());
    if (selected) clearDefault(user.id());
    jdbc.sql("""
        UPDATE provider_profiles SET name=:name,provider_type=:type,base_url=:url,options=CAST(:options AS jsonb),
               is_default=:default,updated_at=:now WHERE id=:id AND owner_id=:owner
        """).param("name", name).param("type", type).param("url", baseUrl).param("options", options)
        .param("default", selected).param("now", OffsetDateTime.now()).param("id", providerId).param("owner", user.id()).update();
    return new ProviderView(providerId, name, type, baseUrl, true, selected);
  }

  @PostMapping("/{providerId}/credentials/rotate")
  @Transactional
  ProviderView rotate(CurrentUser user, @PathVariable UUID providerId, @RequestBody CredentialInput request) {
    var current = owned(user, providerId);
    String apiKey = credential(request.apiKey());
    jdbc.sql("UPDATE provider_profiles SET encrypted_api_key=:key,updated_at=:now WHERE id=:id AND owner_id=:owner")
        .param("key", secrets.encrypt(user.id().toString(), apiKey)).param("now", OffsetDateTime.now())
        .param("id", providerId).param("owner", user.id()).update();
    return new ProviderView(providerId, current.name(), current.providerType(), current.baseUrl(), true, current.defaultProfile());
  }

  @DeleteMapping("/{providerId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Transactional
  void delete(CurrentUser user, @PathVariable UUID providerId) {
    owned(user, providerId);
    boolean inUse = jdbc.sql("SELECT EXISTS(SELECT 1 FROM tasks WHERE provider_profile_id=:id)")
        .param("id", providerId).query(Boolean.class).single();
    if (inUse) throw new ResponseStatusException(HttpStatus.CONFLICT, "PROVIDER_IN_USE");
    jdbc.sql("DELETE FROM provider_profiles WHERE id=:id AND owner_id=:owner").param("id", providerId).param("owner", user.id()).update();
  }

  @PostMapping("/{providerId}/test")
  ProbeView test(CurrentUser user, @PathVariable UUID providerId) {
    var provider = ownedWithCredential(user, providerId);
    try {
      var result = probes.probe(provider.baseUrl(), provider.apiKey());
      return new ProbeView(result.ok(), result.statusCode(), result.latencyMillis(), result.models().size(), result.code());
    } catch (ProviderProbeClient.ProviderProbeException exception) {
      return new ProbeView(false, 0, 0, 0, "CONNECTION_FAILED");
    }
  }

  @PostMapping("/{providerId}/models/discover")
  List<DiscoveredModel> discover(CurrentUser user, @PathVariable UUID providerId) {
    var provider = ownedWithCredential(user, providerId);
    final ProviderProbeClient.ProbeResult result;
    try {
      result = probes.probe(provider.baseUrl(), provider.apiKey());
    } catch (ProviderProbeClient.ProviderProbeException exception) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "PROVIDER_CONNECTION_FAILED");
    }
    if (!result.ok()) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "PROVIDER_" + result.code());
    return result.models().stream().map(model -> new DiscoveredModel(model, model)).toList();
  }

  @PostMapping("/{providerId}/models")
  @ResponseStatus(HttpStatus.CREATED)
  @Transactional
  ModelView addModel(CurrentUser user, @PathVariable UUID providerId, @RequestBody ModelInput request) {
    String modelId = required(request.modelId(), "modelId", 255);
    String displayName = required(request.displayName(), "displayName", 255);
    owned(user, providerId);
    boolean selected = Boolean.TRUE.equals(request.defaultModel());
    if (selected) jdbc.sql("UPDATE provider_models SET is_default=false WHERE provider_profile_id=:id").param("id", providerId).update();
    String capabilities = json(request.capabilities());
    jdbc.sql("""
        INSERT INTO provider_models(provider_profile_id,model_id,display_name,capabilities,is_default)
        VALUES(:id,:model,:name,CAST(:caps AS jsonb),:default)
        ON CONFLICT(provider_profile_id,model_id)
        DO UPDATE SET display_name=EXCLUDED.display_name,capabilities=EXCLUDED.capabilities,is_default=EXCLUDED.is_default
        """).param("id", providerId).param("model", modelId).param("name", displayName)
        .param("caps", capabilities).param("default", selected).update();
    return new ModelView(modelId, displayName, capabilities, selected);
  }

  @GetMapping("/{providerId}/models")
  List<ModelView> models(CurrentUser user, @PathVariable UUID providerId) {
    owned(user, providerId);
    return jdbc.sql("SELECT model_id,display_name,capabilities::text,is_default FROM provider_models WHERE provider_profile_id=:id ORDER BY model_id")
        .param("id", providerId).query((rs, row) -> new ModelView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4))).list();
  }

  @DeleteMapping("/{providerId}/models")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Transactional
  void deleteModel(CurrentUser user, @PathVariable UUID providerId, @RequestParam String modelId) {
    modelId = required(modelId, "modelId", 255);
    owned(user, providerId);
    boolean inUse = jdbc.sql("SELECT EXISTS(SELECT 1 FROM tasks WHERE provider_profile_id=:id AND model_id=:model)")
        .param("id", providerId).param("model", modelId).query(Boolean.class).single();
    if (inUse) throw new ResponseStatusException(HttpStatus.CONFLICT, "PROVIDER_MODEL_IN_USE");
    if (jdbc.sql("DELETE FROM provider_models WHERE provider_profile_id=:id AND model_id=:model")
        .param("id", providerId).param("model", modelId).update() != 1) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PROVIDER_MODEL_NOT_FOUND");
    }
  }

  private OwnedProvider owned(CurrentUser user, UUID id) {
    return jdbc.sql("SELECT name,provider_type,base_url,options::text,is_default FROM provider_profiles WHERE id=:id AND owner_id=:owner")
        .param("id", id).param("owner", user.id()).query((rs, row) -> new OwnedProvider(
            rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5))).optional()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PROVIDER_NOT_FOUND"));
  }

  private ProviderCredential ownedWithCredential(CurrentUser user, UUID id) {
    return jdbc.sql("SELECT base_url,encrypted_api_key FROM provider_profiles WHERE id=:id AND owner_id=:owner")
        .param("id", id).param("owner", user.id()).query((rs, row) -> new ProviderCredential(
            rs.getString(1), secrets.decrypt(user.id().toString(), rs.getString(2)))).optional()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PROVIDER_NOT_FOUND"));
  }

  private void clearDefault(UUID owner) {
    jdbc.sql("UPDATE provider_profiles SET is_default=false WHERE owner_id=:owner AND is_default=true").param("owner", owner).update();
  }

  private String checkedBaseUrl(String value) {
    String baseUrl = required(value, "baseUrl", 2048);
    endpoints.modelsEndpoint(baseUrl);
    while (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
    return baseUrl;
  }

  private static String required(String value, String name, int maxLength) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    String normalized = value.trim();
    if (normalized.length() > maxLength) throw new IllegalArgumentException(name + " is too long");
    return normalized;
  }

  private static String credential(String value) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException("apiKey is required");
    if (value.length() > 16_384) throw new IllegalArgumentException("apiKey is too long");
    return value;
  }

  private static String json(Map<String, Object> value) {
    return value == null ? "{}" : new tools.jackson.databind.ObjectMapper().writeValueAsString(value);
  }

  private record OwnedProvider(String name, String providerType, String baseUrl, String options, boolean defaultProfile) { }
  private record ProviderCredential(String baseUrl, String apiKey) { }
}
