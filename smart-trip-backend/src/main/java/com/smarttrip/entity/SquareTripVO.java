package com.smarttrip.entity;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class SquareTripVO {
    private Long id;
    private String title;
    private String destination;
    private Integer totalDays;
    private BigDecimal totalBudget;
    private String tripStyle;
    private String coverImage;
    private Integer likeCount;
    private Integer cloneCount;
    private String authorNickname;
    private String authorAvatar;
    private LocalDateTime createTime;
}