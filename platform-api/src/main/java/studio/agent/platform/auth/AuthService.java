package studio.agent.platform.auth;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import studio.agent.platform.config.PlatformProperties;
import studio.agent.platform.security.CurrentUser;
import studio.agent.platform.security.TokenDigest;

@Service
public class AuthService {
  private final JdbcClient jdbc; private final PasswordEncoder passwords; private final PlatformProperties properties; private final VerificationDelivery delivery;
  public AuthService(JdbcClient jdbc, PasswordEncoder passwords, PlatformProperties properties, VerificationDelivery delivery) { this.jdbc=jdbc; this.passwords=passwords; this.properties=properties; this.delivery=delivery; }

  @Transactional public IssuedToken setup(String setupToken, String organizationName, String email, String password) {
    if (!constantTimeEquals(properties.setupToken(), setupToken)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "SETUP_TOKEN_INVALID");
    if (jdbc.sql("SELECT count(*) FROM setup_state").query(Integer.class).single() != 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "SETUP_ALREADY_COMPLETED");
    validatePassword(password); var now=OffsetDateTime.now(); var org=UUID.randomUUID(); var user=UUID.randomUUID();
    jdbc.sql("INSERT INTO organizations(id,name,created_at) VALUES(:id,:name,:now)").param("id",org).param("name",required(organizationName)).param("now",now).update();
    jdbc.sql("INSERT INTO users(id,organization_id,email,role,password_hash,email_verified_at,created_at) VALUES(:id,:org,:email,'ADMIN',:password,:now,:now)")
        .param("id",user).param("org",org).param("email",normalizeEmail(email)).param("password",passwords.encode(password)).param("now",now).update();
    jdbc.sql("INSERT INTO setup_state(singleton,completed_at,completed_by) VALUES(TRUE,:now,:user)").param("now",now).param("user",user).update();
    seedTemplates(org,user,now);
    return session(user);
  }

  @Transactional public void register(String email, String password) {
    validatePassword(password); var org=jdbc.sql("SELECT id FROM organizations ORDER BY created_at LIMIT 1").query(UUID.class).optional()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,"SETUP_REQUIRED"));
    var id=UUID.randomUUID(); var now=OffsetDateTime.now(); String normalizedEmail=normalizeEmail(email);
    jdbc.sql("INSERT INTO users(id,organization_id,email,role,password_hash,created_at) VALUES(:id,:org,:email,'MEMBER',:password,:now)")
        .param("id",id).param("org",org).param("email",normalizedEmail).param("password",passwords.encode(password)).param("now",now).update();
    delivery.sendVerification(normalizedEmail, issuePurpose(id,"EMAIL_VERIFY",24));
  }

  @Transactional public void verifyEmail(String token) { consume(token,"EMAIL_VERIFY", "UPDATE users SET email_verified_at=:now WHERE id=:user"); }

  public IssuedToken login(String email, String password) {
    var row=jdbc.sql("SELECT id,password_hash,email_verified_at FROM users WHERE lower(email)=:email AND disabled_at IS NULL")
        .param("email",normalizeEmail(email)).query((rs,n)->new LoginRow(rs.getObject(1,UUID.class),rs.getString(2),rs.getObject(3,OffsetDateTime.class))).optional()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,"INVALID_CREDENTIALS"));
    if (!passwords.matches(password,row.passwordHash())) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"INVALID_CREDENTIALS");
    if (row.verifiedAt()==null) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"EMAIL_NOT_VERIFIED");
    return session(row.id());
  }

  public void requestReset(String email) {
    jdbc.sql("SELECT id,email FROM users WHERE lower(email)=:email AND disabled_at IS NULL").param("email",normalizeEmail(email)).query((rs,n)->new Object[]{rs.getObject(1,UUID.class),rs.getString(2)}).optional()
        .ifPresent(row -> delivery.sendPasswordReset((String) row[1], issuePurpose((UUID) row[0],"PASSWORD_RESET",1)));
  }

  @Transactional public void reset(String token,String newPassword) {
    validatePassword(newPassword); var user=consumeUser(token,"PASSWORD_RESET");
    jdbc.sql("UPDATE users SET password_hash=:password WHERE id=:id").param("password",passwords.encode(newPassword)).param("id",user).update();
    jdbc.sql("UPDATE auth_tokens SET used_at=:now WHERE user_id=:id AND purpose='SESSION' AND used_at IS NULL").param("now",OffsetDateTime.now()).param("id",user).update();
  }

  public void logout(String rawToken) { jdbc.sql("UPDATE auth_tokens SET used_at=:now WHERE token_hash=:hash AND purpose='SESSION'").param("now",OffsetDateTime.now()).param("hash",TokenDigest.hash(rawToken)).update(); }

  private IssuedToken session(UUID user) { var raw=issuePurpose(user,"SESSION",24*30); return new IssuedToken(raw, OffsetDateTime.now().plusDays(30)); }
  private String issuePurpose(UUID user,String purpose,int hours) { var raw=TokenDigest.issue(); jdbc.sql("INSERT INTO auth_tokens(id,user_id,purpose,token_hash,expires_at,created_at) VALUES(:id,:user,:purpose,:hash,:expires,:now)")
      .param("id",UUID.randomUUID()).param("user",user).param("purpose",purpose).param("hash",TokenDigest.hash(raw)).param("expires",OffsetDateTime.now().plusHours(hours)).param("now",OffsetDateTime.now()).update(); return raw; }
  private void consume(String token,String purpose,String update) { var user=consumeUser(token,purpose); jdbc.sql(update).param("now",OffsetDateTime.now()).param("user",user).update(); }
  private UUID consumeUser(String token,String purpose) { var now=OffsetDateTime.now(); var id=jdbc.sql("SELECT id,user_id FROM auth_tokens WHERE token_hash=:hash AND purpose=:purpose AND used_at IS NULL AND expires_at>:now FOR UPDATE")
      .param("hash",TokenDigest.hash(token)).param("purpose",purpose).param("now",now).query((rs,n)->new UUID[]{rs.getObject(1,UUID.class),rs.getObject(2,UUID.class)}).optional()
      .orElseThrow(()->new ResponseStatusException(HttpStatus.BAD_REQUEST,"TOKEN_INVALID_OR_EXPIRED")); jdbc.sql("UPDATE auth_tokens SET used_at=:now WHERE id=:id").param("now",now).param("id",id[0]).update(); return id[1]; }
  private void seedTemplates(UUID org,UUID admin,OffsetDateTime now) {
    for (var seed : new String[][]{
        {"00000000-0000-0000-0000-000000000101","Architecture document","MARKDOWN","# {{title}}\n\n{{content}}","",""},
        {"00000000-0000-0000-0000-000000000102","User guide","MARKDOWN","# {{title}}\n\n{{content}}","",""},
        {"00000000-0000-0000-0000-000000000103","HTML publication","HTML","","<main><h1>{{title}}</h1><section>{{content}}</section></main>","main{max-width:960px;margin:0 auto;padding:2rem;font-family:system-ui,sans-serif}"},
        {"00000000-0000-0000-0000-000000000104","Screenshot set","MARKDOWN","# {{title}}","",""}}) {
      var template=UUID.randomUUID(); jdbc.sql("INSERT INTO templates(id,organization_id,owner_id,skill_id,name,visibility,created_at) VALUES(:id,:org,NULL,:skill,:name,'PUBLIC',:now)").param("id",template).param("org",org).param("skill",UUID.fromString(seed[0])).param("name",seed[1]).param("now",now).update();
      var schema = "00000000-0000-0000-0000-000000000101".equals(seed[0])
          ? "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"title\":{\"type\":\"string\",\"title\":\"文档标题\",\"default\":\"项目架构文档\"},\"audience\":{\"type\":\"string\",\"title\":\"阅读对象\",\"default\":\"开发、测试与运维\"}},\"required\":[\"title\"]}"
          : "{\"type\":\"object\",\"additionalProperties\":false}";
      jdbc.sql("INSERT INTO template_versions(id,template_id,ordinal,output_format,parameter_schema,form_layout,allowed_sections,markdown_template,html_template,css,validation_rules,created_by,created_at) VALUES(:id,:template,1,:format,CAST(:schema AS jsonb),'{}'::jsonb,'[]'::jsonb,NULLIF(:markdown,''),NULLIF(:html,''),NULLIF(:css,''),'[]'::jsonb,:user,:now)").param("id",UUID.randomUUID()).param("template",template).param("format",seed[2]).param("schema",schema).param("markdown",seed[3]).param("html",seed[4]).param("css",seed[5]).param("user",admin).param("now",now).update();
    }
  }
  private static String required(String v){if(v==null||v.isBlank())throw new IllegalArgumentException("value is required");return v.trim();}
  private static String normalizeEmail(String email){var value=required(email).toLowerCase(Locale.ROOT);if(!value.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+"))throw new IllegalArgumentException("email is invalid");return value;}
  private static void validatePassword(String p){if(p==null||p.length()<12||p.length()>128)throw new IllegalArgumentException("password must be 12 to 128 characters");}
  private static boolean constantTimeEquals(String a,String b){return a!=null&&b!=null&&java.security.MessageDigest.isEqual(a.getBytes(java.nio.charset.StandardCharsets.UTF_8),b.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
  private record LoginRow(UUID id,String passwordHash,OffsetDateTime verifiedAt){}
  public record IssuedToken(String accessToken,OffsetDateTime expiresAt){}
}
