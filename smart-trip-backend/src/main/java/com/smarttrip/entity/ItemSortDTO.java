package com.smarttrip.entity;

import lombok.Data;

@Data
public class ItemSortDTO {
    private Long itemId;       // 卡片主键ID
    private Integer sortOrder; // 拖拽后的最新排序值 (1, 2, 3...)
}