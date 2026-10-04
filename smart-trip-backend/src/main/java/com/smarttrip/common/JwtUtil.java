package com.smarttrip.common;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import java.util.Date;

public class JwtUtil {
  // 秘钥（至少 256 位）
  private static java.security.Key KEY;

  public static void configure(String secret) {
    if (secret == null || secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32)
      throw new IllegalStateException("JWT secret must contain at least 32 bytes");
    KEY = Keys.hmacShaKeyFor(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  // 过期时间：7 天 (毫秒)
  private static final long EXPIRE_TIME = 7 * 24 * 60 * 60 * 1000L;

  /** 生成 JWT Token */
  public static String generateToken(Long userId, String username) {
    return generateToken(userId, username, 0);
  }

  public static String generateToken(Long userId, String username, Integer version) {
    return Jwts.builder()
        .setSubject(username)
        .claim("userId", userId)
        .claim("tokenVersion", version == null ? 0 : version)
        .setIssuedAt(new Date())
        .setExpiration(new Date(System.currentTimeMillis() + EXPIRE_TIME))
        .signWith(KEY, SignatureAlgorithm.HS256)
        .compact();
  }

  /** 解析 Token 获取 userId */
  public static Integer getVersionFromToken(String token) {
    try {
      return Jwts.parserBuilder()
          .setSigningKey(KEY)
          .build()
          .parseClaimsJws(token)
          .getBody()
          .get("tokenVersion", Integer.class);
    } catch (Exception e) {
      return null;
    }
  }

  public static Long getUserIdFromToken(String token) {
    try {
      Claims claims =
          Jwts.parserBuilder().setSigningKey(KEY).build().parseClaimsJws(token).getBody();
      return claims.get("userId", Long.class);
    } catch (Exception e) {
      return null;
    }
  }
}
