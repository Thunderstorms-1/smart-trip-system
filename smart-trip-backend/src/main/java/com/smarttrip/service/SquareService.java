package com.smarttrip.service;

import com.smarttrip.entity.SquareTripVO;
import java.util.List;

public interface SquareService {
    List<SquareTripVO> getSquareTrips();
    Long cloneTrip(Long sourceTripId, Long currentUserId);
}