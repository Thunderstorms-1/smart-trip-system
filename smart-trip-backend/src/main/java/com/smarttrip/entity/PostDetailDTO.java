package com.smarttrip.entity;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class PostDetailDTO extends PostSummaryDTO {
  private TripDetailVO trip;
  private boolean liked;
  private boolean favorited;
}
