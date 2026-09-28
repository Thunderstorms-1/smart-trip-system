package com.smarttrip.service;

import com.smarttrip.entity.*;

import java.util.List;

public interface TripService {
    TripDetailVO getTripDetail(Long tripId);
    void updateItem(Long itemId, TripItem item);
    void deleteTripItem(Long itemId);
    TripItem addTripItem(TripItemAddDTO dto);
    void sortItems(Long dayId, List<Long> sortedItemIds);
    void modifyTripByAI(TripModifyDTO dto);
    void reorderItems(List<ItemSortDTO> sortList);

    // 【新增】：天数动态管理
    TripDayVO addTripDay(Long tripId);
    void deleteTripDay(Long dayId);

    List<TripDetailVO> getMyTrips(Long userId);
    void deleteTrip(Long tripId, Long currentUserId);
}