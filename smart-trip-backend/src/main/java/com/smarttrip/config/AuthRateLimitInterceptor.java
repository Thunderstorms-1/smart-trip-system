package com.smarttrip.config;

import com.smarttrip.service.AuthRateLimiter;
import jakarta.servlet.http.*;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuthRateLimitInterceptor implements HandlerInterceptor {
  public static final String ADDRESS_ATTRIBUTE =
      AuthRateLimitInterceptor.class.getName() + ".address";
  private final ClientAddressResolver addresses;
  private final AuthRateLimiter limiter;

  public AuthRateLimitInterceptor(ClientAddressResolver addresses, AuthRateLimiter limiter) {
    this.addresses = addresses;
    this.limiter = limiter;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (!"POST".equals(request.getMethod())) return true;
    if (!(handler instanceof org.springframework.web.method.HandlerMethod endpoint)
        || !com.smarttrip.controller.AuthController.class.isAssignableFrom(endpoint.getBeanType()))
      return true;
    String operation = endpoint.getMethod().getName();
    if (!Set.of("login", "register").contains(operation)) return true;
    String ip = addresses.resolve(request);
    limiter.request(ip, operation.equals("register"));
    request.setAttribute(ADDRESS_ATTRIBUTE, ip);
    return true;
  }
}
