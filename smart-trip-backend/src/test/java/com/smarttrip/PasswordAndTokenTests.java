package com.smarttrip;

import static org.junit.jupiter.api.Assertions.*;

import com.smarttrip.common.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class PasswordAndTokenTests {
  @Test
  void saltedHashVerifiesExactPassword() {
    var encoder = new BCryptPasswordEncoder(4);
    String password = " DemoPassword2026 ";
    String first = encoder.encode(password), second = encoder.encode(password);
    assertNotEquals(password, first);
    assertNotEquals(first, second);
    assertTrue(encoder.matches(password, first));
    assertFalse(encoder.matches(password.trim(), first));
  }

  @Test
  void tokenVersionIsSignedAndTamperingFails() {
    JwtUtil.configure("test-only-secret-at-least-32-bytes-2026");
    String token = JwtUtil.generateToken(42L, "tester", 3);
    assertEquals(42L, JwtUtil.getUserIdFromToken(token));
    assertEquals(3, JwtUtil.getVersionFromToken(token));
    String[] parts = token.split("\\.");
    assertNull(JwtUtil.getUserIdFromToken(parts[0] + "." + parts[1] + ".invalid"));
  }

  @Test
  void shortSigningSecretIsRejected() {
    assertThrows(IllegalStateException.class, () -> JwtUtil.configure("short"));
  }
}
