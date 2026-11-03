package com.smarttrip.rag;

import com.smarttrip.service.AiTaskService;
import com.smarttrip.service.AiTaskStore;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.*;

class RagPreviewStartupTests {
  @Test void previewStartupDoesNotModifyOrRefundExistingTasks() {
    var store=mock(AiTaskStore.class);
    var service=mock(AiTaskService.class,CALLS_REAL_METHODS);
    ReflectionTestUtils.setField(service,"store",store);
    ReflectionTestUtils.setField(service,"ragPreviewOnly",true);
    service.recover();
    verifyNoInteractions(store);
  }
  @Test void ordinaryStartupRetainsExistingTaskRecovery() {
    var store=mock(AiTaskStore.class);
    var service=mock(AiTaskService.class,CALLS_REAL_METHODS);
    ReflectionTestUtils.setField(service,"store",store);
    service.recover();
    verify(store).recover();
  }
}
