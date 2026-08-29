package com.smarttrip.entity;

import lombok.Data;

@Data
public class TripModifyDTO {
    private Long tripId;
    private Integer dayIndex;          // 要修改哪一天
    private String userRequirement;    // 用户的修改要求 (如：把下午的景点换成安静的室内展馆)
}