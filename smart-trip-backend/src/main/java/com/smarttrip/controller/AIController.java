package com.smarttrip.controller;

import com.smarttrip.common.UserContext;
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

    /**
     * SSE 流式生成接口（长连接通信）
     */
    @PostMapping(value = "/trip/stream-generate", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamGenerateTrip(@RequestBody TripGenerateDTO dto) {
        // 设置 5 分钟超时时间 (300,000 毫秒)
        SseEmitter emitter = new SseEmitter(300_000L);
        Long currentUserId = UserContext.getUserId();

        // 绑定超时与完成回调
        emitter.onTimeout(() -> log.warn("SseEmitter 超时"));
        emitter.onError((ex) -> log.error("SseEmitter 异常", ex));

        tripAIService.streamGenerateTrip(dto, currentUserId, emitter);

        return emitter;
    }
}