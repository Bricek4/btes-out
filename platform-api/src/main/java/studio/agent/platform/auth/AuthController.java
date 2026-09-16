package studio.agent.platform.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class AuthController {
  private final AuthService auth;
  AuthController(AuthService auth){this.auth=auth;}
  record Credentials(@Email String email,@Size(min=12,max=128) String password){}
  record Setup(@NotBlank String setupToken,@NotBlank String organizationName,@Email String email,@Size(min=12,max=128) String password){}
  record Token(@NotBlank String token){}
  record Reset(@NotBlank String token,@Size(min=12,max=128) String password){}
  @PostMapping("/setup/first-admin") AuthService.IssuedToken setup(@Valid @RequestBody Setup r){return auth.setup(r.setupToken(),r.organizationName(),r.email(),r.password());}
  @PostMapping("/auth/register") ResponseEntity<Void> register(@Valid @RequestBody Credentials r){auth.register(r.email(),r.password());return ResponseEntity.accepted().build();}
  @PostMapping("/auth/verify-email") ResponseEntity<Void> verify(@Valid @RequestBody Token r){auth.verifyEmail(r.token());return ResponseEntity.noContent().build();}
  @PostMapping("/auth/login") AuthService.IssuedToken login(@Valid @RequestBody Credentials r){return auth.login(r.email(),r.password());}
  @PostMapping("/auth/password-reset/request") ResponseEntity<Void> request(@RequestBody Map<String,String> r){auth.requestReset(r.get("email"));return ResponseEntity.accepted().build();}
  @PostMapping("/auth/password-reset/confirm") ResponseEntity<Void> reset(@Valid @RequestBody Reset r){auth.reset(r.token(),r.password());return ResponseEntity.noContent().build();}
  @PostMapping("/auth/logout") ResponseEntity<Void> logout(@RequestHeader("Authorization") String authorization){auth.logout(authorization.substring(7));return ResponseEntity.noContent().build();}
}
