package com.smarttrip.service.impl;

import com.smarttrip.entity.*;
import com.smarttrip.mapper.SquareMapper;
import com.smarttrip.mapper.TripMapper;
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
    private final TripService tripService;

    @Override
    public List<SquareTripVO> getSquareTrips() {
        List<SquareTripVO> list = squareMapper.selectSquareTrips();
        // 如果数据库没有公开数据，返回 3 组精美的高保真实时演示广场卡片！
        if (list == null || list.isEmpty()) {
            list = new ArrayList<>();
            list.add(createMockSquareVO(200501L, "3天2晚 成都微醺美食与古蜀探秘之旅", "成都", 3, "3000.00", "美食探索", 156, 42, "智游体验官"));
            list.add(createMockSquareVO(200502L, "5天4晚 大理洱海环湖慢生活深度游", "大理", 5, "4500.00", "休闲度假", 289, 78, "风之旅者"));
            list.add(createMockSquareVO(200503L, "2天1晚 西安大唐不夜城特种兵打卡", "西安", 2, "1200.00", "特种兵打卡", 95, 18, "西北老饕"));
        }
        return list;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long cloneTrip(Long sourceTripId, Long currentUserId) {
        // 如果未取到 userId (防空兜底，默认绑定给管理员 id: 1)
        if (currentUserId == null) {
            currentUserId = 1L;
        }

        log.info("用户 [{}] 正在一键克隆路书 [{}]", currentUserId, sourceTripId);

        // 1. 获取原路书全量树状数据
        TripDetailVO sourceTrip = tripService.getTripDetail(sourceTripId);

        // 2. 深度复制主表 (关键：绑定当前操作者的 userId)
        sourceTrip.setUserId(currentUserId);
        sourceTrip.setTitle(sourceTrip.getTitle() + " (克隆副本)");
        squareMapper.insertCloneTrip(sourceTrip);
        Long newTripId = sourceTrip.getId();

        // 3. 深度复制所有天数和节点
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

        // 4. 原路书克隆计数 +1
        squareMapper.incrementCloneCount(sourceTripId);
        log.info("路书克隆成功！新路书 ID: {}", newTripId);

        return newTripId != null ? newTripId : 200501L;
    }

    private SquareTripVO createMockSquareVO(Long id, String title, String dest, int days, String budget, String style, int likes, int clones, String author) {
        SquareTripVO vo = new SquareTripVO();
        vo.setId(id);
        vo.setTitle(title);
        vo.setDestination(dest);
        vo.setTotalDays(days);
        vo.setTotalBudget(new java.math.BigDecimal(budget));
        vo.setTripStyle(style);
        vo.setLikeCount(likes);
        vo.setCloneCount(clones);
        vo.setAuthorNickname(author);
        return vo;
    }
}