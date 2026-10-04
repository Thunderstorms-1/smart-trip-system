package com.smarttrip.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.smarttrip.common.UserContext;
import com.smarttrip.service.*;
import java.util.Map;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class TaskEventsCleanupTests {
  @Test
  void immediateTerminalEventCancelsFutureInstalledAfterCleanup() {
    var store = mock(AiTaskStore.class);
    var trips = mock(WorkbenchService.class);
    var scheduler = mock(ScheduledExecutorService.class);
    var future = mock(ScheduledFuture.class);
    when(store.get("finished")).thenReturn(Map.of("status", "APPLIED"));
    when(trips.encode(any())).thenReturn("{\"status\":\"APPLIED\"}");
    doAnswer(
            invocation -> {
              ((Runnable) invocation.getArgument(0)).run();
              return future;
            })
        .when(scheduler)
        .scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));
    var controller = new TaskEventsController(store, trips, scheduler);
    try {
      for (int n = 0; n < 40; n++) {
        UserContext.setUserId(1L);
        controller.events("finished");
      }
      verify(future, times(40)).cancel(false);
    } finally {
      controller.close();
      UserContext.clear();
    }
  }
}
