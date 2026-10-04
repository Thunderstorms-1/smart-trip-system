package com.smarttrip.service.impl;

import com.smarttrip.common.BusinessException;
import com.smarttrip.entity.TripGenerateDTO;
import com.smarttrip.service.TripAIService;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Legacy compatibility entry point; all provider calls live in AiProvider. */
@Service
public class TripAIServiceImpl implements TripAIService {
  public void streamGenerateTrip(TripGenerateDTO dto, Long userId, SseEmitter emitter) {
    throw new BusinessException(409, "请使用可恢复查询的AI任务接口");
  }
}
