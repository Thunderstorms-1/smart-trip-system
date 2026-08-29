package com.smarttrip.controller;

import com.smarttrip.common.Result;
import com.smarttrip.common.UserContext;
import com.smarttrip.entity.User;
import com.smarttrip.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserMapper userMapper;

    @GetMapping("/info")
    public Result<User> getCurrentUserInfo() {
        // 从 ThreadLocal 中优雅地取出当前登录用户的 ID
        Long currentUserId = UserContext.getUserId();
        User user = userMapper.findById(currentUserId);

        // 敏感字段脱敏，不把密码返给前端
        if (user != null) {
            user.setPassword(null);
        }
        return Result.success(user);
    }
}