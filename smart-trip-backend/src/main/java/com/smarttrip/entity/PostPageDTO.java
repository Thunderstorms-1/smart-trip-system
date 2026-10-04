package com.smarttrip.entity;

import java.util.List;

public record PostPageDTO(List<PostSummaryDTO> items, String nextCursor, boolean hasMore) {}
