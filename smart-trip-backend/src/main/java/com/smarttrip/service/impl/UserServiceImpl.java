package com.smarttrip.service.impl;

import com.smarttrip.common.BusinessException;
import com.smarttrip.common.JwtUtil;
import com.smarttrip.entity.LoginDTO;
import com.smarttrip.entity.User;
import com.smarttrip.mapper.UserMapper;
import com.smarttrip.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;

    @Override
    public Map<String, Object> login(LoginDTO loginDTO) {
        log.info("用户尝试登录: username={}", loginDTO.getUsername());

        // 1. 查询数据库是否存在该用户
        User user = userMapper.findByUsername(loginDTO.getUsername());
        if (user == null) {
            throw new BusinessException("用户不存在，请先注册");
        }

        // 2. 校验密码（目前先比对明文，后续可升为加盐密码）
        if (!user.getPassword().equals(loginDTO.getPassword())) {
            throw new BusinessException("密码错误，请重新输入");
        }

        // 3. 校验账号状态
        if (user.getStatus() == 0) {
            throw new BusinessException("该账号已被冻结，请联系管理员");
        }

        // 4. 生成 JWT Token
        String token = JwtUtil.generateToken(user.getId(), user.getUsername());

        // 5. 组装返回给前端的数据
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("token", token);
        resultMap.put("userId", user.getId());
        resultMap.put("nickname", user.getNickname());
        resultMap.put("avatar", user.getAvatar());
        resultMap.put("aiQuotaDaily", user.getAiQuotaDaily());
        resultMap.put("aiQuotaUsed", user.getAiQuotaUsed());

        log.info("用户登录成功: userId={}, username={}", user.getId(), user.getUsername());
        return resultMap;
    }
}