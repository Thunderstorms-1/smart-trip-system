package com.smarttrip.common;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;

import java.security.Key;
import java.util.Date;

public class JwtUtil {
    // 秘钥（至少 256 位）
    private static final Key KEY = Keys.hmacShaKeyFor("SmartTripSecretKey2026ComputerScienceMustWin!!".getBytes());
    // 过期时间：7 天 (毫秒)
    private static final long EXPIRE_TIME = 7 * 24 * 60 * 60 * 1000L;

    /**
     * 生成 JWT Token
     */
    public static String generateToken(Long userId, String username) {
        return Jwts.builder()
                .setSubject(username)
                .claim("userId", userId)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + EXPIRE_TIME))
                .signWith(KEY, SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * 解析 Token 获取 userId
     */
    public static Long getUserIdFromToken(String token) {
        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(KEY)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
            return claims.get("userId", Long.class);
        } catch (Exception e) {
            return null;
        }
    }
}