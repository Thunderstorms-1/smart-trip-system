package com.smarttrip.entity;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class SquarePost {
    private Long id;
    private Long tripId;
    private Long userId;
    private String authorName;
    private String authorAvatar;
    private String title;
    private String destination;
    private Integer totalDays;
    private BigDecimal totalBudget;
    private String tripStyle;
    private String coverImage;
    private String summary;
    private String snapshotJson;
    private Integer likeCount;
    private Integer cloneCount;
    private Integer isDeleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}