package com.smarttrip.service.impl;

import com.smarttrip.common.PageResult;
import com.smarttrip.entity.*;
import com.smarttrip.mapper.SquareMapper;
import com.smarttrip.service.SquareService;
import com.smarttrip.service.TripService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SquareServiceImpl implements SquareService {

    private final SquareMapper squareMapper;
    private final TripService tripService;

    @Override
    public PageResult<SquareTripVO> searchSquareTrips(SquareQueryDTO query) {
        // 1. 参数默认值保障
        if (query.getPageNum() == null || query.getPageNum() < 1) {
            query.setPageNum(1);
        }
        if (query.getPageSize() == null || query.getPageSize() < 1) {
            query.setPageSize(8);
        }
        if (query.getSortType() == null || query.getSortType().isBlank()) {
            query.setSortType("HOT");
        }

        // 2. 统计总数
        long total = squareMapper.countSquareTrips(query);

        // 3. 计算分页偏移量
        int offset = (query.getPageNum() - 1) * query.getPageSize();
        List<SquareTripVO> list = squareMapper.selectSquareTripsPaged(query, offset);

        // 4. 空数据库默认兜底演示卡片
        if (total == 0 && (query.getKeyword() == null || query.getKeyword().isBlank()) && "全部".equals(query.getTravelStyle())) {
            list = new ArrayList<>();
            list.add(createMockSquareVO(200501L, "3天2晚 成都微醺美食与古蜀探秘之旅", "成都", 3, "3000.00", "美食探索", 156, 42, "智游体验官"));
            list.add(createMockSquareVO(200502L, "5天4晚 大理洱海环湖慢生活深度游", "大理", 5, "4500.00", "休闲度假", 289, 78, "风之旅者"));
            list.add(createMockSquareVO(200503L, "2天1晚 西安大唐不夜城特种兵打卡", "西安", 2, "1200.00", "特种兵打卡", 95, 18, "西北老饕"));
            total = list.size();
        }

        return new PageResult<>(total, query.getPageNum(), query.getPageSize(), list);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long cloneTrip(Long sourceTripId, Long currentUserId) {
        if (currentUserId == null) {
            currentUserId = 1L;
        }
        log.info("用户 [{}] 正在一键克隆公开路书 [{}]", currentUserId, sourceTripId);

        TripDetailVO sourceTrip = tripService.getTripDetail(sourceTripId);
        sourceTrip.setUserId(currentUserId);
        sourceTrip.setTitle(sourceTrip.getTitle() + " (克隆副本)");
        sourceTrip.setIsPublic(0);
        squareMapper.insertCloneTrip(sourceTrip);
        Long newTripId = sourceTrip.getId();

        if (sourceTrip.getDays() != null) {
            for (TripDayVO day : sourceTrip.getDays()) {
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

        squareMapper.incrementCloneCount(sourceTripId);
        return newTripId;
    }

    private SquareTripVO createMockSquareVO(Long id, String title, String dest, int days, String budget, String style, int likes, int clones, String author) {
        SquareTripVO vo = new SquareTripVO();
        vo.setId(id);
        vo.setTitle(title);
        vo.setDestination(dest);
        vo.setTotalDays(days);
        vo.setTotalBudget(new BigDecimal(budget));
        vo.setTripStyle(style);
        vo.setLikeCount(likes);
        vo.setCloneCount(clones);
        vo.setAuthorNickname(author);
        return vo;
    }
}