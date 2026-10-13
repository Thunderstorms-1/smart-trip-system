package com.smarttrip;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.smarttrip.common.*;
import com.smarttrip.config.*;
import com.smarttrip.controller.AuthController;
import com.smarttrip.service.*;
import jakarta.validation.Validation;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AuthRateLimitTests {
  MockHttpServletRequest request(String remote, String forwarded) {
    var request = new MockHttpServletRequest();
    request.setRemoteAddr(remote);
    if (forwarded != null) request.addHeader("X-Forwarded-For", forwarded);
    request.addHeader("Forwarded", "for=203.0.113.100");
    request.addHeader("X-Real-IP", "203.0.113.101");
    return request;
  }

  @Test
  void untrustedPeersCannotChooseTheirOwnIpBucket() {
    var resolver = new ClientAddressResolver(new AuthLimitProperties());
    assertEquals("198.51.100.7", resolver.resolve(request("198.51.100.7", "203.0.113.100")));
    assertEquals("127.0.0.1", resolver.resolve(request("127.0.0.1", "203.0.113.100")));
    assertEquals("unknown", resolver.resolve(request("invalid-client", "203.0.113.100")));
  }

  @Test
  void explicitlyTrustedProxiesUseRightmostUntrustedHop() {
    var properties = new AuthLimitProperties();
    properties.setTrustedProxies(List.of("127.0.0.1", "10.0.0.2", "::1"));
    var resolver = new ClientAddressResolver(properties);
    assertEquals(
        "198.51.100.7",
        resolver.resolve(request("127.0.0.1", "203.0.113.99,198.51.100.7,10.0.0.2")));
    assertEquals("198.51.100.8", resolver.resolve(request("::1", "198.51.100.8")));
    assertEquals("198.51.100.7", resolver.resolve(request("198.51.100.7", "203.0.113.99")));
  }

  @Test
  void malformedOrExcessiveForwardingFallsBackToConnectionPeer() {
    var properties = new AuthLimitProperties();
    properties.setTrustedProxies(List.of("127.0.0.1"));
    var resolver = new ClientAddressResolver(properties);
    for (String value :
        List.of(
            "example.com",
            "999.1.1.1",
            "198.51.100.7,",
            "198.51.100.7\r\nspoof",
            "x".repeat(2049),
            String.join(",", Collections.nCopies(17, "198.51.100.7"))))
      assertEquals("127.0.0.1", resolver.resolve(request("127.0.0.1", value)));
    assertEquals("127.0.0.1", resolver.resolve(request("127.0.0.1", null)));
  }

  @Test
  void ipRepresentationsAreCanonicalAndProxyConfigurationRejectsHostnames() {
    var resolver = new ClientAddressResolver(new AuthLimitProperties());
    assertEquals(
        resolver.resolve(request("::1", null)), resolver.resolve(request("0:0:0:0:0:0:0:1", null)));
    assertEquals("192.0.2.1", resolver.resolve(request("::ffff:192.0.2.1", null)));
    assertEquals("192.0.2.1", resolver.resolve(request("192.000.002.001", null)));
    var properties = new AuthLimitProperties();
    properties.setTrustedProxies(List.of("proxy.example.com"));
    assertThrows(IllegalArgumentException.class, () -> new ClientAddressResolver(properties));
  }

  @Test
  void redisFailureReturns503BeforeAnyLoginOrRegistrationBusinessAction() throws Exception {
    var redis = mock(StringRedisTemplate.class);
    doThrow(new RedisConnectionFailureException("fixture outage"))
        .when(redis)
        .execute(any(DefaultRedisScript.class), anyList(), any(Object[].class));
    var properties = new AuthLimitProperties();
    var limiter = new AuthRateLimiter(redis, properties);
    var users = mock(UserService.class);
    var interceptor = new AuthRateLimitInterceptor(new ClientAddressResolver(properties), limiter);
    var mvc =
        MockMvcBuilders.standaloneSetup(
                new AuthController(users, limiter, mock(com.smarttrip.mapper.UserMapper.class)))
            .addInterceptors(interceptor)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    for (String path : List.of("/api/auth/login", "/api/auth/register"))
      mvc.perform(
              post(path)
                  .contentType("application/json")
                  .content("{\"username\":\"tester\",\"password\":\"Password2026!\"}"))
          .andExpect(status().isServiceUnavailable())
          .andExpect(jsonPath("$.code").value(503));
    verifyNoInteractions(users);
  }

  @Test
  void missingRedisResultAlsoFailsClosedForEachLimiterOperation() {
    var limiter = new AuthRateLimiter(mock(StringRedisTemplate.class), new AuthLimitProperties());
    for (Runnable action :
        List.<Runnable>of(
            () -> limiter.request("198.51.100.1", false),
            () -> limiter.checkLogin("198.51.100.1", "tester"),
            () -> limiter.failedLogin("198.51.100.1", "tester"),
            () -> limiter.succeededLogin("198.51.100.1", "tester")))
      assertEquals(503, assertThrows(BusinessException.class, action::run).getCode());
  }

  @Test
  void invalidLimitSettingsCannotSilentlyDisableProtection() {
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      var validator = factory.getValidator();
      var properties = new AuthLimitProperties();
      assertTrue(validator.validate(properties).isEmpty());
      properties.setLoginIpLimit(0);
      properties.setCooldownSeconds(86400);
      properties.setKeyPrefix("invalid prefix");
      assertEquals(3, validator.validate(properties).size());
    }
  }
}
