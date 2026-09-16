package studio.agent.platform.product;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;
import studio.agent.platform.security.CurrentUser;
import studio.agent.platform.security.SecretBox;

@RestController
@RequestMapping("/api/v1/login-profiles")
public class LoginProfileController {
  private final JdbcClient jdbc; private final SecretBox secrets;
  public LoginProfileController(JdbcClient jdbc, SecretBox secrets){this.jdbc=jdbc;this.secrets=secrets;}
  record Input(String reference,String name,String loginUrl,String loginPath,String usernameLocator,String passwordLocator,String submitLocator,String username,String password) {}
  record View(UUID id,String reference,String name,String loginUrl,String loginPath,String usernameLocator,String passwordLocator,String submitLocator) {}
  @GetMapping List<View> list(CurrentUser user){return jdbc.sql("SELECT id,reference,name,login_url,login_path,username_locator,password_locator,submit_locator FROM login_profiles WHERE owner_id=:owner ORDER BY created_at DESC").param("owner",user.id()).query((rs,n)->new View(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8))).list();}
  @PostMapping @ResponseStatus(HttpStatus.CREATED) View create(CurrentUser user,@RequestBody Input r){for(var v:new String[]{r.reference(),r.name(),r.loginUrl(),r.usernameLocator(),r.passwordLocator(),r.submitLocator(),r.username(),r.password()})if(v==null||v.isBlank())throw new IllegalArgumentException("login profile fields are required");var id=UUID.randomUUID();var now=OffsetDateTime.now();jdbc.sql("INSERT INTO login_profiles(id,owner_id,reference,name,login_url,login_path,username_locator,password_locator,submit_locator,encrypted_username,encrypted_password,created_at,updated_at) VALUES(:id,:owner,:ref,:name,:url,:path,:userLoc,:passLoc,:submit,:user,:pass,:now,:now)").param("id",id).param("owner",user.id()).param("ref",r.reference().trim()).param("name",r.name().trim()).param("url",r.loginUrl().trim()).param("path",r.loginPath()).param("userLoc",r.usernameLocator()).param("passLoc",r.passwordLocator()).param("submit",r.submitLocator()).param("user",secrets.encrypt(user.id().toString(),r.username())).param("pass",secrets.encrypt(user.id().toString(),r.password())).param("now",now).update();return new View(id,r.reference().trim(),r.name().trim(),r.loginUrl().trim(),r.loginPath(),r.usernameLocator(),r.passwordLocator(),r.submitLocator());}
}
