package com.smarttrip.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smarttrip.common.BusinessException;
import com.smarttrip.common.PageResult;
import com.smarttrip.entity.*;
import com.smarttrip.mapper.SquareMapper;
import com.smarttrip.mapper.TripMapper;
import com.smarttrip.mapper.UserMapper;
import com.smarttrip.service.SquareService;
import com.smarttrip.service.TripService;
import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SquareServiceImpl implements SquareService {

  private final SquareMapper squareMapper;
  private final com.smarttrip.service.CommunityService community;
  private final TripMapper tripMapper;
  private final UserMapper userMapper;
  private final TripService tripService;
  private final ObjectMapper objectMapper = new ObjectMapper();

  /** 【彻底去硬编码重构】：直接从 MySQL 动态拉取 is_featured=1 的官方精选路线 */
  @Override
  public List<FeaturedThemeVO> getFeaturedThemedTrips() {
    log.info("【动态精选模板拉取】正在从 MySQL 查询 is_featured=1 的官方精选路线资产");
    List<FeaturedThemeVO> list = squareMapper.selectFeaturedThemedTrips();
    return list != null ? list : Collections.emptyList();
  }

  @Override
  public PageResult<SquareTripVO> searchSquareTrips(SquareQueryDTO query) {
    if (query.getPageNum() == null || query.getPageNum() < 1) query.setPageNum(1);
    if (query.getPageSize() == null || query.getPageSize() < 1) query.setPageSize(8);
    query.setPageSize(Math.min(query.getPageSize(), 50));
    if (query.getSortType() == null || query.getSortType().isBlank()) query.setSortType("HOT");

    long total = squareMapper.countSquareTrips(query);
    int offset = (query.getPageNum() - 1) * query.getPageSize();
    List<SquareTripVO> list = squareMapper.selectSquareTripsPaged(query, offset);

    return new PageResult<>(total, query.getPageNum(), query.getPageSize(), list);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Long publishOrSyncTrip(Long tripId, Long currentUserId) {
    community.publish(tripId);
    return tripId;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void unpublishTrip(Long tripId, Long currentUserId) {
    community.unpublish(tripId);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Long cloneFromSnapshot(Long squarePostId, Long currentUserId) {
    return community.clonePost(squarePostId).getId();
  }

  private Long cloneFromPrivateTrip(TripDetailVO sourceTrip, Long currentUserId) {
    throw new BusinessException(403, "私密路书不能通过广场克隆");
  }
}
