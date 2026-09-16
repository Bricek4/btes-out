package studio.agent.platform.config;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot 4 no longer bundles Flyway's auto-configuration module. Keep migration startup
 * explicit so a freshly provisioned database is ready before scheduled outbox work starts.
 */
@Configuration
class DatabaseMigrationConfiguration {
  @Bean(initMethod = "migrate")
  Flyway flyway(DataSource dataSource) {
    return Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .load();
  }
}
