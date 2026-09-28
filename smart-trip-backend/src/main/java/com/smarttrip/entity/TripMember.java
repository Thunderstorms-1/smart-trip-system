package com.smarttrip.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class TripMember {
    private Long id;
    private Long tripId;
    private Long userId;
    private String role;              // OWNER, EDITOR, VIEWER
    private String nicknameSnapshot;
    private String avatarSnapshot;
    private Integer isDeleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}