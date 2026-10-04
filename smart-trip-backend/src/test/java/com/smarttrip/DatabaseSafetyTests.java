package com.smarttrip;

import static org.junit.jupiter.api.Assertions.*;

import com.smarttrip.config.DatabaseSafety;
import org.junit.jupiter.api.Test;

class DatabaseSafetyTests {
  @Test
  void refusesOriginalDatabaseBeforeMigration() {
    assertThrows(IllegalStateException.class, () -> DatabaseSafety.requireDemoSchema("smart_trip"));
    assertThrows(IllegalStateException.class, () -> DatabaseSafety.requireDemoSchema(null));
    assertDoesNotThrow(() -> DatabaseSafety.requireDemoSchema("smart_trip_demo"));
    assertDoesNotThrow(() -> DatabaseSafety.requireDemoSchema("smart_trip_test"));
  }
}
