package com.smarttrip.entity;

import lombok.Data;

@Data
public class RegisterDTO {
    private String username; // 登录账号
    private String password; // 登录密码
    private String nickname; // 个性昵称（可选）
}