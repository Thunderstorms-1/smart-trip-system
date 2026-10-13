package com.smarttrip;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smarttrip.common.*;
import com.smarttrip.config.AuthLimitProperties;
import com.smarttrip.entity.*;
import com.smarttrip.service.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@EnabledIfEnvironmentVariable(named = "SMARTTRIP_INTEGRATION", matches = "true")
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:mysql://localhost:3306/smart_trip_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
      "security.jwt-secret=test-only-jwt-secret-at-least-32-bytes",
      "ai.llm.enabled=false",
      "geo.enabled=false",
      "security.auth-limits.login-ip-limit=6",
      "security.auth-limits.register-ip-limit=2",
      "security.auth-limits.pair-failure-limit=2",
      "security.auth-limits.account-failure-limit=4",
      "security.auth-limits.cooldown-seconds=5"
    })
class AuthRateLimitIntegrationTests {
  static final String PREFIX = "smarttrip:test:auth:" + UUID.randomUUID() + ":";
  static final AtomicInteger addresses = new AtomicInteger();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("security.auth-limits.key-prefix", () -> PREFIX);
  }

  @Autowired WebApplicationContext context;
  @Autowired UserService users;
  @Autowired JdbcTemplate db;
  @Autowired StringRedisTemplate redis;
  @Autowired AuthRateLimiter limiter;
  MockMvc mvc;
  String ip, username;
  Long user;

  @BeforeEach
  void setup() {
    assertEquals("smart_trip_test", db.queryForObject("SELECT DATABASE()", String.class));
    ip = "198.51.100." + addresses.incrementAndGet();
    var r = new RegisterDTO();
    username = "limit_" + System.nanoTime();
    r.setUsername(username);
    r.setPassword("LimitTest2026!");
    user = users.register(r);
    mvc =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(context.getBean(com.smarttrip.config.TraceIdFilter.class))
            .build();
  }

  @AfterEach
  void clear() {
    UserContext.clear();
    assertTrue(PREFIX.startsWith("smarttrip:test:auth:"));
    var keys = redis.keys(PREFIX + "*");
    if (keys != null && !keys.isEmpty()) redis.delete(keys);
  }

  MockHttpServletRequestBuilder request(String path, String address, String body) {
    return post(path)
        .contentType("application/json")
        .content(body)
        .with(
            r -> {
              r.setRemoteAddr(address);
              return r;
            });
  }

  MvcResult login(String address, String name, String password) throws Exception {
    return mvc.perform(
            request(
                "/api/auth/login",
                address,
                new ObjectMapper()
                    .writeValueAsString(Map.of("username", name, "password", password))))
        .andReturn();
  }

  void assertStatus(int expected, MvcResult result) throws Exception {
    assertEquals(
        expected, result.getResponse().getStatus(), result.getResponse().getContentAsString());
  }

  @Test
  void normalLoginKeepsJwtProfileAndDoesNotAlterAccountState() throws Exception {
    var response = login(ip, username, "LimitTest2026!");
    assertStatus(200, response);
    var data =
        new ObjectMapper().readTree(response.getResponse().getContentAsString()).path("data");
    String token = data.path("token").asText();
    assertFalse(token.isBlank());
    mvc.perform(get("/api/user/info").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());
    assertEquals(
        1, db.queryForObject("SELECT status FROM sys_user WHERE id=?", Integer.class, user));
    assertEquals(
        0, db.queryForObject("SELECT token_version FROM sys_user WHERE id=?", Integer.class, user));
  }

  @Test
  void failedKnownAndUnknownAccountsShareMessagesAndPairCooldown() throws Exception {
    var wrong = login(ip, username, "wrong");
    assertStatus(400, wrong);
    String other = "203.0.113.10";
    var absent = login(other, "ghost_account", "wrong");
    assertStatus(400, absent);
    var mapper = new ObjectMapper();
    assertEquals(
        mapper.readTree(wrong.getResponse().getContentAsString()).path("message"),
        mapper.readTree(absent.getResponse().getContentAsString()).path("message"));
    assertStatus(400, login(ip, username.toUpperCase(Locale.ROOT), "wrong"));
    var blocked = login(ip, username, "LimitTest2026!");
    assertStatus(429, blocked);
    assertTrue(Long.parseLong(blocked.getResponse().getHeader("Retry-After")) > 0);
    assertStatus(400, login(other, "ghost_account", "wrong"));
    assertStatus(429, login(other, "GHOST_ACCOUNT", "wrong"));
    assertStatus(200, login("203.0.113.11", username, "LimitTest2026!"));
  }

  @Test
  void changingIpsCannotEvadeAccountFailureLimit() throws Exception {
    for (String address : List.of(ip, "203.0.113.20"))
      for (int n = 0; n < 2; n++) assertStatus(400, login(address, username, "wrong"));
    assertStatus(429, login("203.0.113.21", username, "LimitTest2026!"));
    assertStatus(400, login("203.0.113.21", "independent_account", "wrong"));
  }

  @Test
  void successfulLoginClearsOnlyItsOwnPairFailureCounter() throws Exception {
    assertStatus(400, login(ip, username, "wrong"));
    assertStatus(200, login(ip, username, "LimitTest2026!"));
    assertStatus(400, login(ip, username, "wrong"));
    assertStatus(400, login(ip, username, "wrong"));
    assertStatus(429, login(ip, username, "LimitTest2026!"));
    // Three account-wide failures remain; success cannot erase attacks from other IPs.
    assertStatus(400, login("203.0.113.30", username, "wrong"));
    assertStatus(429, login("203.0.113.31", username, "LimitTest2026!"));
  }

  @Test
  void malformedLoginConsumesIpQuotaAndForgedHeadersCannotChangeIt() throws Exception {
    for (int n = 0; n < 6; n++)
      mvc.perform(
              request("/api/auth/login", ip, "{")
                  .header("X-Forwarded-For", "203.0.113." + (100 + n))
                  .header("Forwarded", "for=203.0.113." + (100 + n)))
          .andExpect(status().isBadRequest());
    mvc.perform(request("/api/auth/login", ip, "{").header("X-Forwarded-For", "203.0.113.200"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"))
        .andExpect(jsonPath("$.code").value(429));
    assertStatus(200, login("203.0.113.201", username, "LimitTest2026!"));
  }

  @Test
  void registrationCountsFailedAttemptsAndLeavesLoginBudgetIndependent() throws Exception {
    mvc.perform(request("/api/auth/register", ip, "{")).andExpect(status().isBadRequest());
    String registered = "registered_" + System.nanoTime();
    var payload = Map.of("username", registered, "password", "LimitTest2026!");
    mvc.perform(request("/api/auth/register", ip, new ObjectMapper().writeValueAsString(payload)))
        .andExpect(status().isOk());
    mvc.perform(
            request(
                "/api/auth/register",
                ip,
                new ObjectMapper()
                    .writeValueAsString(
                        Map.of(
                            "username",
                            "blocked_" + System.nanoTime(),
                            "password",
                            "LimitTest2026!"))))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"));
    assertStatus(200, login(ip, registered, "LimitTest2026!"));
    assertEquals(
        1,
        db.queryForObject(
            "SELECT COUNT(*) FROM sys_user WHERE username=?", Integer.class, registered));
  }

  @Test
  void invalidFieldValidationDoesNotCountAsPasswordFailure() throws Exception {
    for (int n = 0; n < 3; n++)
      mvc.perform(request("/api/auth/login", ip, "{\"username\":\"" + username + "\"}"))
          .andExpect(status().isBadRequest());
    assertStatus(200, login(ip, username, "LimitTest2026!"));
  }

  @Test
  void concurrentRedisAdmissionCannotOverrunIpQuota() throws Exception {
    var pool = Executors.newFixedThreadPool(12);
    var gate = new CountDownLatch(1);
    var admitted = new AtomicInteger();
    var rejected = new AtomicInteger();
    try {
      List<Future<?>> jobs = new ArrayList<>();
      for (int n = 0; n < 12; n++)
        jobs.add(
            pool.submit(
                () -> {
                  try {
                    gate.await();
                    limiter.request(ip, false);
                    admitted.incrementAndGet();
                  } catch (AuthRateLimitException limit) {
                    rejected.incrementAndGet();
                  } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                  }
                }));
      gate.countDown();
      for (var job : jobs) job.get(5, TimeUnit.SECONDS);
      assertEquals(6, admitted.get());
      assertEquals(6, rejected.get());
      assertTrue(redis.getExpire(PREFIX + "login:" + CommunityService.hash(ip)) > 0);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void lateInFlightFailuresAndBlockedRequestsDoNotExtendCooldown() throws Exception {
    limiter.failedLogin(ip, username);
    limiter.failedLogin(ip, username);
    var keys = redis.keys(PREFIX + "pair:*:block");
    assertEquals(1, keys.size());
    String block = keys.iterator().next();
    long before = redis.getExpire(block, TimeUnit.MILLISECONDS);
    Thread.sleep(120);
    for (int n = 0; n < 8; n++) {
      limiter.failedLogin(ip, username);
      assertThrows(AuthRateLimitException.class, () -> limiter.checkLogin(ip, username));
    }
    long after = redis.getExpire(block, TimeUnit.MILLISECONDS);
    assertTrue(after <= before - 100, "Requests must not renew the existing cooldown");
    assertTrue(after > 0);
  }

  @Test
  void ipWindowAndCooldownRecoverThroughActualRedisExpiry() throws Exception {
    var properties = new AuthLimitProperties();
    properties.setKeyPrefix(PREFIX + "expiry:");
    properties.setLoginIpLimit(1);
    properties.setLoginIpWindowSeconds(1);
    properties.setPairFailureLimit(1);
    properties.setCooldownSeconds(1);
    var shortLimits = new AuthRateLimiter(redis, properties);
    shortLimits.request(ip, false);
    assertThrows(AuthRateLimitException.class, () -> shortLimits.request(ip, false));
    shortLimits.failedLogin(ip, username);
    assertThrows(AuthRateLimitException.class, () -> shortLimits.checkLogin(ip, username));
    Thread.sleep(1100);
    assertDoesNotThrow(() -> shortLimits.request(ip, false));
    assertDoesNotThrow(() -> shortLimits.checkLogin(ip, username));
    shortLimits.failedLogin(ip, username);
    assertThrows(AuthRateLimitException.class, () -> shortLimits.checkLogin(ip, username));
  }

  @Test
  void preflightDoesNotConsumeAuthenticationQuota() throws Exception {
    for (int n = 0; n < 8; n++)
      mvc.perform(
              options("/api/auth/login")
                  .header("Origin", "http://127.0.0.1:5173")
                  .header("Access-Control-Request-Method", "POST")
                  .with(
                      r -> {
                        r.setRemoteAddr(ip);
                        return r;
                      }))
          .andExpect(status().isOk());
    assertStatus(200, login(ip, username, "LimitTest2026!"));
  }

  @Test
  void pathParametersCannotBypassRegistrationOrLoginAdmission() throws Exception {
    for (int n = 0; n < 2; n++)
      mvc.perform(request("/api/auth/register;variant=" + n, ip, "{"))
          .andExpect(status().isBadRequest());
    mvc.perform(request("/api/auth/register;variant=next", ip, "{"))
        .andExpect(status().isTooManyRequests());
    for (int n = 0; n < 6; n++)
      mvc.perform(request("/api/auth/login;variant=" + n, ip, "{"))
          .andExpect(status().isBadRequest());
    mvc.perform(request("/api/auth/login;variant=next", ip, "{"))
        .andExpect(status().isTooManyRequests());
  }

  @Test
  void databaseEquivalentUsernameSpellingsCannotBypassFailureCounters() throws Exception {
    String accent = username.replace("limit_", "límít_");
    assertEquals(
        user, db.queryForObject("SELECT id FROM sys_user WHERE username=?", Long.class, accent));
    assertStatus(400, login(ip, username, "wrong"));
    assertStatus(400, login(ip, accent, "wrong"));
    assertStatus(429, login(ip, username.toUpperCase(Locale.ROOT), "LimitTest2026!"));
    assertStatus(200, login("203.0.113.80", accent, "LimitTest2026!"));
  }
}
