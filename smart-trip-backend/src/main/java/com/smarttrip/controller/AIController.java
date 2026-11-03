package com.smarttrip.controller;

import com.smarttrip.entity.TripGenerateDTO;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/ai")
public class AIController {

  /** SSE 流式生成接口（长连接通信） */
  @PostMapping(value = "/trip/stream-generate", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter streamGenerateTrip(@RequestBody TripGenerateDTO dto) {
    throw new com.smarttrip.common.BusinessException(409, "请使用AI任务接口，任务状态可恢复查询");
  }
}
