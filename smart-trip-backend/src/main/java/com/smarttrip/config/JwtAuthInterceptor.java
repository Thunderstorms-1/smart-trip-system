package com.smarttrip.config;

import com.smarttrip.common.JwtUtil;
import com.smarttrip.common.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Slf4j
@Component
public class JwtAuthInterceptor implements HandlerInterceptor {
  @org.springframework.beans.factory.annotation.Autowired
  private com.smarttrip.mapper.UserMapper userMapper;

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    // 1. 放行 OPTIONS 预检请求（解决前端跨域问题）
    if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
      return true;
    }

    // 2. 从 Header 中获取 Authorization: Bearer <Token>
    String authHeader = request.getHeader("Authorization");
    if (authHeader != null && authHeader.startsWith("Bearer ")) {
      String token = authHeader.substring(7);
      Long userId = JwtUtil.getUserIdFromToken(token);

      com.smarttrip.entity.User account = userId == null ? null : userMapper.findById(userId);
      if (account != null
          && !Integer.valueOf(0).equals(account.getStatus())
          && java.util.Objects.equals(
              account.getTokenVersion(), JwtUtil.getVersionFromToken(token))) {
        // Token 合法，存入当前线程上下文
        UserContext.setUserId(userId);
        log.debug("JWT 鉴权通过: userId={}", userId);
        return true;
      }
    }

    String uri = request.getRequestURI();
    boolean publicGet =
        "GET".equals(request.getMethod())
            && (uri.matches("/api/community/posts(?:/[0-9]+(?:/comments)?)?")
                || uri.matches("/api/community/share/[A-Za-z0-9_-]+")
                || uri.matches("/api/user/public/[0-9]+")
                || uri.equals("/api/capabilities")
                || uri.equals("/api/square/search")
                || uri.equals("/api/square/trips"));
    if (publicGet) return true;
    // 3. Token 无效或未携带，返回 401 状态码
    log.warn("JWT 鉴权拦截: 未携带有效 Token, path={}", request.getRequestURI());
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    return false;
  }

  @Override
  public void afterCompletion(
      HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
    // 请求处理完毕，务必清理 ThreadLocal
    UserContext.clear();
  }
}
