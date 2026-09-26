package com.smarttrip.service;

import com.smarttrip.common.PageResult;
import com.smarttrip.entity.SquareQueryDTO;
import com.smarttrip.entity.SquareTripVO;

public interface SquareService {
    PageResult<SquareTripVO> searchSquareTrips(SquareQueryDTO query);
    Long publishOrSyncTrip(Long tripId, Long currentUserId);
    void unpublishTrip(Long tripId, Long currentUserId);
    Long cloneFromSnapshot(Long squarePostId, Long currentUserId);
}