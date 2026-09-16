package studio.agent.browser;

import com.microsoft.playwright.Playwright;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import studio.agent.browser.security.NavigationPolicy;
import studio.agent.browser.session.BrowserPlatformGateway;
import studio.agent.browser.session.BrowserLimits;
import studio.agent.browser.session.PlaywrightBrowserService;
import studio.agent.browser.session.SessionRegistry;
import studio.agent.browser.web.WorkerTokenFilter;

@Configuration
public class BrowserWorkerConfiguration {
  @Bean PlaywrightBrowserService browserService(BrowserPlatformGateway gateway,
      @Value("${BROWSER_LOCAL_MODE:false}") boolean localMode,
      @Value("${BROWSER_ALLOWED_HOSTS:}") String allowedHosts,
      @Value("${BROWSER_ALLOWED_ORIGINS:}") String allowedOrigins) {
    Playwright playwright = Playwright.create();
    var browser = playwright.chromium().launch(new com.microsoft.playwright.BrowserType.LaunchOptions().setHeadless(true));
    var policy = new NavigationPolicy(localMode, split(allowedHosts), split(allowedOrigins),
        host -> List.of(java.net.InetAddress.getAllByName(host)));
    return new PlaywrightBrowserService(playwright, browser, policy, new SessionRegistry(16, Duration.ofMinutes(10), Clock.systemUTC()), gateway, gateway,
        new BrowserLimits(Duration.ofSeconds(10), Duration.ofSeconds(20), 2, 1280, 720, 10_000_000, 20_000_000));
  }
  @Bean FilterRegistrationBean<WorkerTokenFilter> browserWorkerToken(@Value("${AGENT_WORKER_TOKEN}") String token) {
    var registration = new FilterRegistrationBean<>(new WorkerTokenFilter(token)); registration.addUrlPatterns("/internal/*"); registration.setOrder(-100); return registration;
  }

  private static List<String> split(String value) {
    if (value == null || value.isBlank()) return List.of();
    return Arrays.stream(value.split(","))
        .map(String::trim).filter(item -> !item.isBlank()).toList();
  }
}
