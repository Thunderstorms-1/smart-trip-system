package com.smarttrip.service;

import com.smarttrip.common.PageResult;
import com.smarttrip.entity.SquareQueryDTO;
import com.smarttrip.entity.SquareTripVO;
import java.util.List;

public interface SquareService {
    Long cloneTrip(Long sourceTripId, Long currentUserId);
    PageResult<SquareTripVO> searchSquareTrips(SquareQueryDTO query);
}
