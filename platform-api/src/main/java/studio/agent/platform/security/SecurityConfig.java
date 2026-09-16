package studio.agent.platform.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

@Configuration
class SecurityConfig {
  @Bean SecurityFilterChain filterChain(HttpSecurity http, SessionAuthenticationFilter sessions, WorkerTokenGuard workerTokens) throws Exception {
    return http.csrf(csrf -> csrf.disable()).cors(Customizer.withDefaults())
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/api/v1/setup/**", "/api/v1/auth/**", "/internal/**", "/actuator/health").permitAll()
            .anyRequest().authenticated())
        .addFilterBefore(new InternalWorkerTokenFilter(workerTokens), SessionAuthenticationFilter.class)
        .addFilterBefore(sessions, AnonymousAuthenticationFilter.class)
        .httpBasic(basic -> basic.disable()).formLogin(form -> form.disable()).build();
  }
}
