package com.smarttrip.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class TripPackingItem {
    private Long id;
    private Long tripId;
    private String category;    // DOC:证件, CLOTHES:衣物, MEDICINE:药品, ELECTRONIC:数码, OTHER:其他
    private String itemName;    // 物品名 (如：身份证、防晒霜、充电宝)
    private Integer isChecked;  // 0:未打包, 1:已打包
    private Integer isDeleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}