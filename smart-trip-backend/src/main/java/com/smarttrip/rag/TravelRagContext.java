package com.smarttrip.rag;

import java.util.Map;

/** promptContext is private task state; only snapshot may be returned to clients. */
public record TravelRagContext(String promptContext, Map<String, Object> snapshot) {}
