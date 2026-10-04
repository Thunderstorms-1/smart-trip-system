package com.smarttrip.controller;

import com.smarttrip.entity.TripGenerateDTO;
import com.smarttrip.service.TripAIService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j // 加上这行注解，log 就生效了！
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AIController {

  private final TripAIService tripAIService;

  /** SSE 流式生成接口（长连接通信） */
  @PostMapping(value = "/trip/stream-generate", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter streamGenerateTrip(@RequestBody TripGenerateDTO dto) {
    throw new com.smarttrip.common.BusinessException(409, "请使用AI任务接口，任务状态可恢复查询");
  }
}
