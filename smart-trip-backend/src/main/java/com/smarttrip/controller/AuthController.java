package com.smarttrip.controller;

import com.smarttrip.common.Result;
import com.smarttrip.entity.LoginDTO;
import com.smarttrip.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@CrossOrigin // 允许跨域
public class AuthController {

    private final UserService userService;

    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody LoginDTO loginDTO) {
        Map<String, Object> loginData = userService.login(loginDTO);
        return Result.success(loginData);
    }
}