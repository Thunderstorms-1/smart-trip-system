package com.smarttrip.service;

import com.smarttrip.entity.LoginDTO;
import java.util.Map;

public interface UserService {
    Map<String, Object> login(LoginDTO loginDTO);
}