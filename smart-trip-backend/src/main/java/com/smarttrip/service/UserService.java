package com.smarttrip.service;

import com.smarttrip.entity.LoginDTO;
import com.smarttrip.entity.RegisterDTO;
import com.smarttrip.entity.UserFootprintVO;

import java.util.Map;

public interface UserService {
    Map<String, Object> login(LoginDTO loginDTO);
    Long register(RegisterDTO registerDTO);
    UserFootprintVO getUserFootprintStats(Long userId);
}