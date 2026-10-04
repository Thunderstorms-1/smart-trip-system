package com.smarttrip.service.impl;

import com.smarttrip.common.BusinessException;
import com.smarttrip.entity.*;
import com.smarttrip.service.TripService;
import com.smarttrip.service.WorkbenchService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Read compatibility only. All draft mutations use the versioned workbench. */
@Service
@RequiredArgsConstructor
public class TripServiceImpl implements TripService {
  private final WorkbenchService workbench;

  private BusinessException retired() {
    return new BusinessException(410, "旧路书写接口已停用，请使用带版本号的工作台保存");
  }

  @Override
  public TripDetailVO getTripDetail(Long id) {
    return workbench.load(id);
  }

  @Override
  public List<TripDetailVO> getMyTrips(Long user) {
    return workbench.mine();
  }

  @Override
  public void deleteTrip(Long id, Long user) {
    workbench.delete(id);
  }

  @Override
  public void updateItem(Long id, TripItem item) {
    throw retired();
  }

  @Override
  public void deleteTripItem(Long id) {
    throw retired();
  }

  @Override
  public TripItem addTripItem(TripItemAddDTO dto) {
    throw retired();
  }

  @Override
  public void sortItems(Long id, List<Long> ids) {
    throw retired();
  }

  @Override
  public void modifyTripByAI(TripModifyDTO dto) {
    throw retired();
  }

  @Override
  public void reorderItems(List<ItemSortDTO> items) {
    throw retired();
  }

  @Override
  public TripDayVO addTripDay(Long id) {
    throw retired();
  }

  @Override
  public void deleteTripDay(Long id) {
    throw retired();
  }
}
