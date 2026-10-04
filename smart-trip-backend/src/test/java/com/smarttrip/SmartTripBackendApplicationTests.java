package com.smarttrip;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfEnvironmentVariable(named = "SMARTTRIP_INTEGRATION", matches = "true")
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:mysql://localhost:3306/smart_trip_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
      "security.jwt-secret=test-only-jwt-secret-at-least-32-bytes",
      "ai.llm.enabled=false",
      "geo.enabled=false"
    })
class SmartTripBackendApplicationTests {
  @Autowired JdbcTemplate db;

  @Test
  void isolatedTestDatabaseMigratesAndStarts() {
    assertEquals("smart_trip_test", db.queryForObject("SELECT DATABASE()", String.class));
    assertEquals(
        8,
        db.queryForObject(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE success=1", Integer.class));
  }
}
