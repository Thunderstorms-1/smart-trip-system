package com.smarttrip.service;

import com.smarttrip.entity.TripGenerateDTO;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface TripAIService {
    void streamGenerateTrip(TripGenerateDTO dto, Long userId, SseEmitter emitter);
}