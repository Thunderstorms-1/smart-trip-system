package com.smarttrip.service;

import com.smarttrip.entity.TripDetailVO;
import com.smarttrip.entity.TripItem;
import com.smarttrip.entity.TripModifyDTO;
import java.util.List;

public interface TripService {
    TripDetailVO getTripDetail(Long tripId);
    void updateItem(Long itemId, TripItem item);
    void deleteTripItem(Long itemId);
    void sortItems(Long dayId, List<Long> sortedItemIds);
    void modifyTripByAI(TripModifyDTO dto);
}