package studio.agent.platform.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

@Configuration
class SecurityConfig {
  @Bean UserDetailsService noPasswordLoginUsers() {
    // Credentials are verified by SessionAuthenticationFilter against the database. Supplying an
    // empty service prevents Spring Boot from generating and advertising a development password.
    return username -> { throw new UsernameNotFoundException("database session authentication is required"); };
  }

  @Bean FilterRegistrationBean<SessionAuthenticationFilter> disableServletRegistration(SessionAuthenticationFilter filter) {
    var registration = new FilterRegistrationBean<>(filter);
    registration.setEnabled(false);
    return registration;
  }

  @Bean SecurityFilterChain filterChain(HttpSecurity http, SessionAuthenticationFilter sessions, WorkerTokenGuard workerTokens) throws Exception {
    return http.csrf(csrf -> csrf.disable()).cors(Customizer.withDefaults())
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/api/v1/setup/**", "/api/v1/auth/**", "/internal/**", "/actuator/health").permitAll()
            .anyRequest().authenticated())
        // Both application filters are inserted relative to a framework-owned anchor. Anchoring
        // one custom filter relative to another is rejected by Spring Security 7 because the
        // custom filter has no registered order during chain construction.
        .addFilterBefore(new InternalWorkerTokenFilter(workerTokens), AuthorizationFilter.class)
        .addFilterBefore(sessions, AuthorizationFilter.class)
        .httpBasic(basic -> basic.disable()).formLogin(form -> form.disable()).build();
  }
}
