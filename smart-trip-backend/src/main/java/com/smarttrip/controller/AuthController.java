package com.smarttrip.controller;

import com.smarttrip.common.Result;
import com.smarttrip.entity.LoginDTO;
import com.smarttrip.entity.RegisterDTO;
import com.smarttrip.service.UserService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@CrossOrigin
public class AuthController {

  private final UserService userService;
  private final com.smarttrip.service.AuthRateLimiter limiter;
  private final com.smarttrip.mapper.UserMapper accounts;

  // 1. 用户登录
  @PostMapping("/login")
  public Result<Map<String, Object>> login(
      @RequestBody LoginDTO loginDTO, jakarta.servlet.http.HttpServletRequest request) {
    String ip = limitedAddress(request);
    String account = loginDTO.getUsername();
    // Use the stored username for every database-equivalent spelling, including accent variants.
    var existing = account == null ? null : accounts.findByUsername(account);
    if (existing != null) account = existing.getUsername();
    limiter.checkLogin(ip, account);
    try {
      Map<String, Object> loginData = userService.login(loginDTO);
      limiter.succeededLogin(ip, account);
      return Result.success(loginData);
    } catch (com.smarttrip.common.AuthenticationFailedException failure) {
      limiter.failedLogin(ip, account);
      throw failure;
    }
  }

  // 2. 用户注册
  @PostMapping("/register")
  public Result<Long> register(
      @RequestBody RegisterDTO registerDTO, jakarta.servlet.http.HttpServletRequest request) {
    limitedAddress(request);
    Long userId = userService.register(registerDTO);
    return Result.success(userId);
  }

  private String limitedAddress(jakarta.servlet.http.HttpServletRequest request) {
    String ip =
        (String)
            request.getAttribute(com.smarttrip.config.AuthRateLimitInterceptor.ADDRESS_ATTRIBUTE);
    if (ip == null) throw new com.smarttrip.common.BusinessException(503, "认证保护服务暂不可用，请稍后重试");
    return ip;
  }
}
