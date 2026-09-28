package com.smarttrip.service;

import com.smarttrip.common.PageResult;
import com.smarttrip.entity.FeaturedThemeVO;
import com.smarttrip.entity.SquareQueryDTO;
import com.smarttrip.entity.SquareTripVO;
import java.util.List;

public interface SquareService {
    PageResult<SquareTripVO> searchSquareTrips(SquareQueryDTO query);
    Long publishOrSyncTrip(Long tripId, Long currentUserId);
    void unpublishTrip(Long tripId, Long currentUserId);
    Long cloneFromSnapshot(Long squarePostId, Long currentUserId);

    // 【核心新增】：获取官方精选四大黄金主题路线灵感库
    List<FeaturedThemeVO> getFeaturedThemedTrips();
}