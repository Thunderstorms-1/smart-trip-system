package com.smarttrip.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class User {
  private String bio;
  private String travelPreferences;
  private Integer tokenVersion;
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
