package com.smarttrip.controller;

import com.smarttrip.common.Result;
import com.smarttrip.entity.LoginDTO;
import com.smarttrip.entity.RegisterDTO;
import com.smarttrip.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@CrossOrigin
public class AuthController {

    private final UserService userService;

    // 1. 用户登录
    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody LoginDTO loginDTO) {
        Map<String, Object> loginData = userService.login(loginDTO);
        return Result.success(loginData);
    }

    // 2. 用户注册
    @PostMapping("/register")
    public Result<Long> register(@RequestBody RegisterDTO registerDTO) {
        Long userId = userService.register(registerDTO);
        return Result.success(userId);
    }
}