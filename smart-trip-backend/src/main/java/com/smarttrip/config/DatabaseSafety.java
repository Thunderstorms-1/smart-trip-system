package com.smarttrip.config;

import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Stop before Flyway can mutate any database outside the agreed demo/test schemas. */
@Configuration
public class DatabaseSafety {
  public static void requireDemoSchema(String catalog) {
    if (!"smart_trip_demo".equals(catalog) && !"smart_trip_test".equals(catalog))
      throw new IllegalStateException(
          "Only smart_trip_demo and smart_trip_test are allowed for this implementation");
  }

  @Bean
  FlywayMigrationStrategy migrationStrategy() {
    return flyway -> {
      try (var c = flyway.getConfiguration().getDataSource().getConnection()) {
        requireDemoSchema(c.getCatalog());
      } catch (java.sql.SQLException e) {
        throw new IllegalStateException("Unable to verify migration target", e);
      }
      flyway.migrate();
    };
  }
}
