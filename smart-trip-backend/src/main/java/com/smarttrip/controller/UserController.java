package com.smarttrip.controller;

import com.smarttrip.common.Result;
import com.smarttrip.common.UserContext;
import com.smarttrip.entity.User;
import com.smarttrip.entity.UserFootprintVO;
import com.smarttrip.mapper.UserMapper;
import com.smarttrip.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserMapper userMapper;
    private final UserService userService;

    @GetMapping("/info")
    public Result<User> getCurrentUserInfo() {
        Long currentUserId = UserContext.getUserId();
        User user = userMapper.findById(currentUserId);
        if (user != null) {
            user.setPassword(null);
        }
        return Result.success(user);
    }

    // 获取当前登录用户的个人足迹与旅行成就大屏数据
    @GetMapping("/footprint-stats")
    public Result<UserFootprintVO> getUserFootprintStats() {
        Long currentUserId = UserContext.getUserId();
        return Result.success(userService.getUserFootprintStats(currentUserId));
    }
}