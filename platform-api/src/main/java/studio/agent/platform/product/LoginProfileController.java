package studio.agent.platform.product;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;
import studio.agent.platform.security.CurrentUser;
import studio.agent.platform.security.SecretBox;
import studio.agent.contracts.LoginLocator;

@RestController
@RequestMapping("/api/v1/login-profiles")
public class LoginProfileController {
  private final JdbcClient jdbc; private final SecretBox secrets;
  public LoginProfileController(JdbcClient jdbc, SecretBox secrets){this.jdbc=jdbc;this.secrets=secrets;}
  record Input(String reference,String name,String loginUrl,String loginPath,LoginLocator usernameLocator,LoginLocator passwordLocator,LoginLocator submitLocator,String username,String password) {}
  record View(UUID id,String reference,String name,String loginUrl,String loginPath,LoginLocator usernameLocator,LoginLocator passwordLocator,LoginLocator submitLocator) {}
  @GetMapping List<View> list(CurrentUser user){return jdbc.sql("SELECT id,reference,name,login_url,login_path,username_locator::text,password_locator::text,submit_locator::text FROM login_profiles WHERE owner_id=:owner ORDER BY created_at DESC").param("owner",user.id()).query((rs,n)->new View(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),locator(rs.getString(6)),locator(rs.getString(7)),locator(rs.getString(8)))).list();}
  @PostMapping @ResponseStatus(HttpStatus.CREATED) View create(CurrentUser user,@RequestBody Input r){if(r.reference()==null||r.name()==null||r.loginUrl()==null||r.usernameLocator()==null||r.passwordLocator()==null||r.submitLocator()==null||r.username()==null||r.password()==null)throw new IllegalArgumentException("login profile fields are required");var id=UUID.randomUUID();var now=OffsetDateTime.now();jdbc.sql("INSERT INTO login_profiles(id,owner_id,reference,name,login_url,login_path,username_locator,password_locator,submit_locator,encrypted_username,encrypted_password,created_at,updated_at) VALUES(:id,:owner,:ref,:name,:url,:path,CAST(:userLoc AS jsonb),CAST(:passLoc AS jsonb),CAST(:submit AS jsonb),:user,:pass,:now,:now)").param("id",id).param("owner",user.id()).param("ref",r.reference().trim()).param("name",r.name().trim()).param("url",r.loginUrl().trim()).param("path",r.loginPath()).param("userLoc",json(r.usernameLocator())).param("passLoc",json(r.passwordLocator())).param("submit",json(r.submitLocator())).param("user",secrets.encrypt(user.id().toString(),r.username())).param("pass",secrets.encrypt(user.id().toString(),r.password())).param("now",now).update();return new View(id,r.reference().trim(),r.name().trim(),r.loginUrl().trim(),r.loginPath(),r.usernameLocator(),r.passwordLocator(),r.submitLocator());}
  private static String json(LoginLocator locator){try{return new tools.jackson.databind.ObjectMapper().writeValueAsString(locator);}catch(tools.jackson.core.JacksonException e){throw new IllegalArgumentException("locator is invalid",e);}}
  private static LoginLocator locator(String json){try{return new tools.jackson.databind.ObjectMapper().readValue(json,LoginLocator.class);}catch(tools.jackson.core.JacksonException e){throw new IllegalStateException("stored locator is invalid",e);}}
}
