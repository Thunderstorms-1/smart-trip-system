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

import java.util.ArrayList;
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

    /**
     * 【核心实现 1】：发布/同步当前行程至广场（生成不可变快照）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long publishOrSyncTrip(Long tripId, Long currentUserId) {
        log.info("用户 [{}] 执行发布/同步快照至广场: tripId={}", currentUserId, tripId);

        // 1. 权限校验
        TripDetailVO currentTripTree = tripService.getTripDetail(tripId);
        if (!currentTripTree.getUserId().equals(currentUserId)) {
            throw new BusinessException(403, "只能发布或同步属于您自己的行程！");
        }

        User author = userMapper.findById(currentUserId);
        String authorName = author != null ? author.getNickname() : "旅行探险家";
        String authorAvatar = author != null ? author.getAvatar() : null;

        // 2. 将当前父子数据树（包含天数和全部卡片）序列化为不可变 JSON 快照
        String snapshotJson;
        try {
            snapshotJson = objectMapper.writeValueAsString(currentTripTree);
        } catch (Exception e) {
            log.error("生成路书快照序列化失败", e);
            throw new BusinessException(500, "生成路书快照失败");
        }

        // 3. 构建/更新快照记录
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

        // 4. 将源行程标记为公开状态
        squareMapper.updateTripPublicStatus(tripId, 1);
        log.info("路书快照发布/同步成功: tripId={}", tripId);
        return tripId;
    }

    /**
     * 【核心实现 2】：将行程设为私密（从广场下架快照）
     */
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

    /**
     * 【核心实现 3】：从不可变快照反序列化并一键克隆（彻底解耦源数据）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long cloneFromSnapshot(Long squarePostId, Long currentUserId) {
        if (currentUserId == null) currentUserId = 1L;
        log.info("用户 [{}] 从广场快照 [{}] 执行一键克隆", currentUserId, squarePostId);

        // 1. 读取快照实体
        SquarePost post = squareMapper.selectPostById(squarePostId);
        if (post == null || post.getSnapshotJson() == null) {
            throw new BusinessException(404, "该公开路书已下架或快照不存在");
        }

        // 2. 反序列化快照为完整的路书树状对象
        TripDetailVO snapshotTree;
        try {
            snapshotTree = objectMapper.readValue(post.getSnapshotJson(), TripDetailVO.class);
        } catch (Exception e) {
            log.error("反序列化路书快照失败", e);
            throw new BusinessException(500, "解析路书快照失败");
        }

        // 3. 克隆主表
        snapshotTree.setUserId(currentUserId);
        snapshotTree.setTitle(snapshotTree.getTitle() + " (克隆副本)");
        snapshotTree.setIsPublic(0); // 克隆出的副本默认私有
        squareMapper.insertCloneTrip(snapshotTree);
        Long newTripId = snapshotTree.getId();

        // 4. 克隆所有天数与卡片
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

        // 5. 原发布帖克隆量 +1
        squareMapper.incrementPostCloneCount(squarePostId);
        log.info("从快照克隆全新路书成功！新 TripId: {}", newTripId);
        return newTripId;
    }
}