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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SquareServiceImpl implements SquareService {

    private final SquareMapper squareMapper;
    private final TripMapper tripMapper;
    private final UserMapper userMapper;
    private final TripService tripService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 【彻底去硬编码重构】：直接从 MySQL 动态拉取 is_featured=1 的官方精选路线
     */
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
        if (query.getSortType() == null || query.getSortType().isBlank()) query.setSortType("HOT");

        long total = squareMapper.countSquareTrips(query);
        int offset = (query.getPageNum() - 1) * query.getPageSize();
        List<SquareTripVO> list = squareMapper.selectSquareTripsPaged(query, offset);

        return new PageResult<>(total, query.getPageNum(), query.getPageSize(), list);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long publishOrSyncTrip(Long tripId, Long currentUserId) {
        log.info("用户 [{}] 执行发布/同步快照至广场: tripId={}", currentUserId, tripId);
        TripDetailVO currentTripTree = tripService.getTripDetail(tripId);
        if (!currentTripTree.getUserId().equals(currentUserId)) {
            throw new BusinessException(403, "只能发布或同步属于您自己的行程！");
        }

        User author = userMapper.findById(currentUserId);
        String authorName = author != null ? author.getNickname() : "旅行探险家";
        String authorAvatar = author != null ? author.getAvatar() : null;

        String snapshotJson;
        try {
            snapshotJson = objectMapper.writeValueAsString(currentTripTree);
        } catch (Exception e) {
            log.error("生成路书快照序列化失败", e);
            throw new BusinessException(500, "生成路书快照失败");
        }

        SquarePost existingPost = squareMapper.selectPostByTripId(tripId);
        SquarePost post = new SquarePost();
        post.setTripId(tripId);
        post.setUserId(currentUserId);
        post.setAuthorName(authorName);
        post.setAuthorAvatar(authorAvatar);
        post.setTitle(currentTripTree.getTitle());
        post.setDestination(currentTripTree.getDestination());
        post.setTotalDays(currentTripTree.getTotalDays());
        post.setTotalBudget(currentTripTree.getTotalBudget());
        post.setTripStyle(currentTripTree.getTripStyle());
        post.setSummary(currentTripTree.getSummary());
        post.setSnapshotJson(snapshotJson);

        if (existingPost == null) {
            squareMapper.insertPost(post);
        } else {
            squareMapper.updatePostSnapshot(post);
        }

        squareMapper.updateTripPublicStatus(tripId, 1);
        log.info("路书快照发布/同步成功: tripId={}", tripId);
        return tripId;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unpublishTrip(Long tripId, Long currentUserId) {
        TripDetailVO currentTripTree = tripService.getTripDetail(tripId);
        if (!currentTripTree.getUserId().equals(currentUserId)) {
            throw new BusinessException(403, "只能下架属于您自己的行程！");
        }

        squareMapper.softDeletePostByTripId(tripId);
        squareMapper.updateTripPublicStatus(tripId, 0);
        log.info("路书已成功从广场下架: tripId={}", tripId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long cloneFromSnapshot(Long squarePostId, Long currentUserId) {
        if (currentUserId == null) currentUserId = 1L;
        log.info("用户 [{}] 从广场快照 [{}] 执行一键克隆", currentUserId, squarePostId);

        SquarePost post = squareMapper.selectPostById(squarePostId);
        if (post == null || post.getSnapshotJson() == null) {
            // 如果在 square_post 没找到，尝试从 trip_plan 种子库直接克隆
            TripDetailVO seedTrip = tripMapper.selectTripById(squarePostId);
            if (seedTrip != null) {
                return cloneFromPrivateTrip(seedTrip, currentUserId);
            }
            throw new BusinessException(404, "该公开路书已下架或快照不存在");
        }

        TripDetailVO snapshotTree;
        try {
            snapshotTree = objectMapper.readValue(post.getSnapshotJson(), TripDetailVO.class);
        } catch (Exception e) {
            log.error("反序列化路书快照失败", e);
            throw new BusinessException(500, "解析路书快照失败");
        }

        snapshotTree.setUserId(currentUserId);
        snapshotTree.setTitle(snapshotTree.getTitle() + " (克隆副本)");
        snapshotTree.setIsPublic(0);
        squareMapper.insertCloneTrip(snapshotTree);
        Long newTripId = snapshotTree.getId();

        if (snapshotTree.getDays() != null) {
            for (TripDayVO day : snapshotTree.getDays()) {
                day.setTripId(newTripId);
                squareMapper.insertCloneDay(day);
                Long newDayId = day.getId();

                if (day.getItems() != null) {
                    for (TripItem item : day.getItems()) {
                        item.setTripId(newTripId);
                        item.setDayId(newDayId);
                        squareMapper.insertCloneItem(item);
                    }
                }
            }
        }

        squareMapper.incrementPostCloneCount(squarePostId);
        return newTripId;
    }

    private Long cloneFromPrivateTrip(TripDetailVO sourceTrip, Long currentUserId) {
        sourceTrip.setUserId(currentUserId);
        sourceTrip.setTitle(sourceTrip.getTitle() + " (克隆副本)");
        sourceTrip.setIsPublic(0);
        squareMapper.insertCloneTrip(sourceTrip);
        return sourceTrip.getId();
    }
}