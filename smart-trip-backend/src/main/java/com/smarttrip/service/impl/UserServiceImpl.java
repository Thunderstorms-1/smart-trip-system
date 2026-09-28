package com.smarttrip.service.impl;

import com.smarttrip.common.BusinessException;
import com.smarttrip.common.JwtUtil;
import com.smarttrip.entity.LoginDTO;
import com.smarttrip.entity.RegisterDTO;
import com.smarttrip.entity.User;
import com.smarttrip.entity.UserFootprintVO;
import com.smarttrip.mapper.TripMapper;
import com.smarttrip.mapper.UserMapper;
import com.smarttrip.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    // 在 UserServiceImpl 中注入 TripMapper：
    private final TripMapper tripMapper;

    /**
     * 获取用户个人足迹与旅行成就大屏数据
     */
    @Override
    public UserFootprintVO getUserFootprintStats(Long userId) {
        UserFootprintVO vo = new UserFootprintVO();
        if (userId == null) return vo;

        // 1. 组装 KPI 指标
        Map<String, Object> kpis = tripMapper.selectUserTripKpis(userId);
        vo.setTotalTrips(kpis != null && kpis.get("totalTrips") != null ? ((Number) kpis.get("totalTrips")).intValue() : 0);
        vo.setTotalDays(kpis != null && kpis.get("totalDays") != null ? ((Number) kpis.get("totalDays")).intValue() : 0);
        vo.setTotalBudget(kpis != null && kpis.get("totalBudget") != null ? new BigDecimal(kpis.get("totalBudget").toString()) : BigDecimal.ZERO);

        // 2. 组装点亮城市（绑定已知城市 GIS 基准点）
        List<Map<String, Object>> cityStats = tripMapper.selectUserCityStats(userId);
        List<UserFootprintVO.CityFootprintItem> cityItems = new ArrayList<>();
        if (cityStats != null) {
            for (Map<String, Object> map : cityStats) {
                String city = (String) map.get("destination");
                int count = ((Number) map.get("count")).intValue();

                UserFootprintVO.CityFootprintItem item = new UserFootprintVO.CityFootprintItem();
                item.setCityName(city);
                item.setTripCount(count);

                double[] coords = resolveCityBaseCoord(city);
                item.setLongitude(coords[0]);
                item.setLatitude(coords[1]);
                cityItems.add(item);
            }
        }
        vo.setLitCities(cityItems);
        vo.setExploredCitiesCount(cityItems.size());

        // 3. 组装风格雷达图
        vo.setStyleRadarData(tripMapper.selectUserStyleStats(userId));
        return vo;
    }

    private double[] resolveCityBaseCoord(String city) {
        if (city == null) return new double[]{104.06, 30.57};
        if (city.contains("成都")) return new double[]{104.06, 30.57};
        if (city.contains("大理")) return new double[]{100.22, 25.60};
        if (city.contains("丽江")) return new double[]{100.22, 26.87};
        if (city.contains("苏州")) return new double[]{120.58, 31.29};
        if (city.contains("杭州")) return new double[]{120.15, 30.27};
        if (city.contains("黄山")) return new double[]{118.33, 29.71};
        if (city.contains("西安")) return new double[]{108.93, 34.34};
        if (city.contains("北京")) return new double[]{116.40, 39.90};
        if (city.contains("上海")) return new double[]{121.47, 31.23};
        if (city.contains("甘孜")) return new double[]{101.96, 30.04};
        return new double[]{108.93, 34.34};
    }

    @Override
    public Map<String, Object> login(LoginDTO loginDTO) {
        log.info("用户尝试登录: username={}", loginDTO.getUsername());

        // 1. 查询数据库是否存在该用户
        User user = userMapper.findByUsername(loginDTO.getUsername());
        if (user == null) {
            throw new BusinessException("用户不存在，请先注册");
        }

        // 2. 校验密码
        if (!user.getPassword().equals(loginDTO.getPassword())) {
            throw new BusinessException("密码错误，请重新输入");
        }

        // 3. 校验账号状态
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BusinessException("该账号已被冻结，请联系管理员");
        }

        // 4. 生成 JWT Token
        String token = JwtUtil.generateToken(user.getId(), user.getUsername());

        // 5. 组装返回给前端的数据
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("token", token);
        resultMap.put("userId", user.getId());
        resultMap.put("username", user.getUsername());
        resultMap.put("nickname", user.getNickname());
        resultMap.put("avatar", user.getAvatar());
        resultMap.put("aiQuotaDaily", user.getAiQuotaDaily());
        resultMap.put("aiQuotaUsed", user.getAiQuotaUsed());

        log.info("用户登录成功: userId={}, username={}", user.getId(), user.getUsername());
        return resultMap;
    }

    /**
     * 用户注册逻辑（非空防御 + 长度合规 + 唯一排重 + 昵称兜底）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long register(RegisterDTO registerDTO) {
        // 1. 非空防御校验
        if (registerDTO == null
                || registerDTO.getUsername() == null || registerDTO.getUsername().isBlank()
                || registerDTO.getPassword() == null || registerDTO.getPassword().isBlank()) {
            throw new BusinessException(400, "账号和密码不能为空");
        }

        String username = registerDTO.getUsername().trim();
        String password = registerDTO.getPassword().trim();

        // 2. 密码长度合规性检查 (不少于 6 位)
        if (password.length() < 6) {
            throw new BusinessException(400, "密码长度不能少于 6 位");
        }

        // 3. 用户名唯一性排重
        User existingUser = userMapper.findByUsername(username);
        if (existingUser != null) {
            throw new BusinessException(400, "该用户名已被注册，请更换其它账号");
        }

        // 4. 组装 User 实体落库
        User user = new User();
        user.setUsername(username);
        user.setPassword(password);

        // 昵称兜底处理
        if (registerDTO.getNickname() != null && !registerDTO.getNickname().isBlank()) {
            user.setNickname(registerDTO.getNickname().trim());
        } else {
            user.setNickname("旅行探险家");
        }

        userMapper.insertUser(user);
        log.info("用户注册成功: userId={}, username={}", user.getId(), user.getUsername());

        return user.getId();
    }
}