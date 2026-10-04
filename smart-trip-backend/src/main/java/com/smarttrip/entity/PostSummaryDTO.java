package com.smarttrip.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class PostSummaryDTO {
  private Long id;
  private Long authorId;
  private String authorName;
  private String authorAvatar;
  private String title;
  private String destination;
  private Integer totalDays;
  private BigDecimal totalBudget;
  private String summary;
  private String tripStyle;
  private LocalDateTime publishedAt;
  private Long likeCount;
  private Long favoriteCount;
  private Long commentCount;
  private Long cloneCount;
}
