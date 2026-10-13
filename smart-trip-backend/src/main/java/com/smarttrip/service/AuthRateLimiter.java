package com.smarttrip.service;

import com.smarttrip.common.*;
import com.smarttrip.config.AuthLimitProperties;
import java.util.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
public class AuthRateLimiter {
  private static final DefaultRedisScript<Long> REQUEST =
      new DefaultRedisScript<>(
          """
          local count = redis.call('INCR', KEYS[1])
          if count == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[2]) end
          if count > tonumber(ARGV[1]) then return math.max(1, math.ceil(redis.call('PTTL', KEYS[1])/1000)) end
          return 0
          """,
          Long.class);
  private static final DefaultRedisScript<Long> CHECK =
      new DefaultRedisScript<>(
          """
          local ttl = math.max(redis.call('PTTL', KEYS[1]), redis.call('PTTL', KEYS[2]))
          if ttl > 0 then return math.max(1, math.ceil(ttl/1000)) end
          return 0
          """,
          Long.class);
  private static final DefaultRedisScript<Long> FAILURE =
      new DefaultRedisScript<>(
          """
          for n = 1, 2 do
            local count = redis.call('INCR', KEYS[n])
            if count == 1 then redis.call('PEXPIRE', KEYS[n], ARGV[3]) end
            if count >= tonumber(ARGV[n]) then
              local created = redis.call('SET', KEYS[n+2], '1', 'NX', 'PX', ARGV[4])
              if created then redis.call('DEL', KEYS[n]) end
            end
          end
          return 0
          """,
          Long.class);
  private static final DefaultRedisScript<Long> SUCCESS =
      new DefaultRedisScript<>("return redis.call('DEL', KEYS[1], KEYS[2])", Long.class);
  private final StringRedisTemplate redis;
  private final AuthLimitProperties properties;

  public AuthRateLimiter(StringRedisTemplate redis, AuthLimitProperties properties) {
    this.redis = redis;
    this.properties = properties;
  }

  public void request(String ip, boolean register) {
    int limit = register ? properties.getRegisterIpLimit() : properties.getLoginIpLimit();
    int window =
        register ? properties.getRegisterIpWindowSeconds() : properties.getLoginIpWindowSeconds();
    reject(
        execute(
            REQUEST,
            List.of(key(register ? "register" : "login", ip)),
            "" + limit,
            milliseconds(window)));
  }

  public void checkLogin(String ip, String username) {
    reject(execute(CHECK, List.of(pair(ip, username) + ":block", account(username) + ":block")));
  }

  public void failedLogin(String ip, String username) {
    String pair = pair(ip, username), account = account(username);
    execute(
        FAILURE,
        List.of(pair + ":fail", account + ":fail", pair + ":block", account + ":block"),
        "" + properties.getPairFailureLimit(),
        "" + properties.getAccountFailureLimit(),
        milliseconds(properties.getFailureWindowSeconds()),
        milliseconds(properties.getCooldownSeconds()));
  }

  public void succeededLogin(String ip, String username) {
    String pair = pair(ip, username);
    execute(SUCCESS, List.of(pair + ":fail", pair + ":block"));
  }

  private String key(String scope, String value) {
    return properties.getKeyPrefix() + scope + ":" + CommunityService.hash(value);
  }

  private String account(String username) {
    return key("account", normalize(username));
  }

  private String pair(String ip, String username) {
    return key("pair", ip + "\n" + normalize(username));
  }

  private String normalize(String username) {
    return username == null ? "" : username.strip().toLowerCase(Locale.ROOT);
  }

  private String milliseconds(int seconds) {
    return Long.toString(seconds * 1000L);
  }

  private void reject(long wait) {
    if (wait > 0) throw new AuthRateLimitException(wait);
  }

  private long execute(DefaultRedisScript<Long> script, List<String> keys, String... args) {
    try {
      Long result = redis.execute(script, keys, (Object[]) args);
      if (result == null) throw new IllegalStateException("Missing limiter result");
      return result;
    } catch (RuntimeException unavailable) {
      throw new BusinessException(503, "认证保护服务暂不可用，请稍后重试");
    }
  }
}
