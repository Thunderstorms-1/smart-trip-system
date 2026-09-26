package com.smarttrip.service;

import com.smarttrip.entity.TripDetailVO;
import com.smarttrip.entity.TripItem;
import com.smarttrip.entity.TripItemAddDTO;
import com.smarttrip.entity.TripModifyDTO;
import java.util.List;

public interface TripService {
    TripDetailVO getTripDetail(Long tripId);
    TripItem addTripItem(TripItemAddDTO dto);
    void updateItem(Long itemId, TripItem item);
    void sortItems(Long dayId, List<Long> sortedItemIds);
    void modifyTripByAI(TripModifyDTO dto);
    void deleteTripItem(Long itemId);
}