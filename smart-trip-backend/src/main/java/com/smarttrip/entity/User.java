package com.smarttrip.entity;

import lombok.Data;
import java.time.LocalDateTime;
import java.time.LocalDate;

@Data
public class User {
    private Long id;
    private String username;
    private String password;
    private String nickname;
    private String avatar;
    private String email;
    private String homeCity;
    private Integer aiQuotaDaily;
    private Integer aiQuotaUsed;
    private LocalDate quotaResetDate;
    private Integer status;
    private Integer isDeleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}