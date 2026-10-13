package com.smarttrip;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ch.qos.logback.classic.*;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.smarttrip.common.*;
import com.smarttrip.config.*;
import jakarta.servlet.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.*;
import org.springframework.mock.web.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.HandlerMapping;

class ObservabilityTests {
  static class Capture extends ListAppender<ILoggingEvent> {
    @Override
    protected void append(ILoggingEvent event) {
      event.prepareForDeferredProcessing();
      super.append(event);
    }
  }

  @RestController
  static class FixtureController {
    @GetMapping("/api/test/ok")
    Map<String, String> ok() {
      return Map.of("result", "ok");
    }

    @GetMapping("/api/community/share/{token}")
    Map<String, String> share() {
      return Map.of("result", "ok");
    }

    @GetMapping("/api/test/failure")
    void failure() {
      throw new IllegalStateException(
          "internal sensitive SQL", new IllegalArgumentException("nested root cause"));
    }

    @GetMapping("/api/test/status/{code}")
    void status(@PathVariable int code) {
      throw new BusinessException(code, "安全业务提示");
    }

    @GetMapping("/api/test/rate")
    void rate() {
      throw new AuthRateLimitException(15);
    }
  }

  MockMvc mvc;
  Capture capture;
  ch.qos.logback.classic.Logger root;

  @BeforeEach
  void setup() {
    MDC.clear();
    root =
        (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    capture = new Capture();
    capture.setContext(root.getLoggerContext());
    capture.start();
    root.addAppender(capture);
    mvc =
        MockMvcBuilders.standaloneSetup(new FixtureController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .addFilters(new TraceIdFilter(new ObservabilityProperties()))
            .build();
    capture.list.clear();
  }

  @AfterEach
  void clear() {
    root.detachAppender(capture);
    capture.stop();
    MDC.clear();
  }

  List<ILoggingEvent> http() {
    return capture.list.stream()
        .filter(e -> e.getFormattedMessage().startsWith("HTTP request finished"))
        .toList();
  }

  @Test
  void unknown500LogsCompleteCauseAndCorrelatesSafeResponse() throws Exception {
    var response =
        mvc.perform(get("/api/test/failure").header("X-Trace-Id", "failure_trace_2026"))
            .andExpect(status().isInternalServerError())
            .andExpect(header().string("X-Trace-Id", "failure_trace_2026"))
            .andExpect(jsonPath("$.message").value("服务暂时不可用，请稍后重试"))
            .andReturn()
            .getResponse();
    assertFalse(response.getContentAsString().contains("sensitive"));
    var error =
        capture.list.stream().filter(e -> e.getLevel() == Level.ERROR).findFirst().orElseThrow();
    assertNotNull(error.getThrowableProxy());
    assertNotNull(error.getThrowableProxy().getCause());
    assertEquals("nested root cause", error.getThrowableProxy().getCause().getMessage());
    assertTrue(error.getThrowableProxy().getStackTraceElementProxyArray().length > 0);
    assertEquals("failure_trace_2026", error.getMDCPropertyMap().get("traceId"));
    assertEquals("failure_trace_2026", http().get(0).getMDCPropertyMap().get("traceId"));
    assertTrue(http().get(0).getFormattedMessage().contains("status=500"));
    assertNull(MDC.get("traceId"));
  }

  static Stream<String> invalidIds() {
    return Stream.of("", "short", "bad trace value", "中文追踪编号", "bad\ntrace", "x".repeat(65));
  }

  @ParameterizedTest
  @MethodSource("invalidIds")
  void invalidClientIdentifiersAreReplacedWithoutLoggingThem(String invalid) throws Exception {
    String trace =
        mvc.perform(get("/api/test/ok").header("X-Trace-Id", invalid))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getHeader("X-Trace-Id");
    assertTrue(trace.matches("[a-f0-9]{32}"));
    assertEquals(trace, http().get(0).getMDCPropertyMap().get("traceId"));
    assertNull(MDC.get("traceId"));
  }

  @Test
  void absentAndDuplicateHeadersGenerateIndependentIdentifiers() throws Exception {
    String first =
        mvc.perform(get("/api/test/ok")).andReturn().getResponse().getHeader("X-Trace-Id");
    String second =
        mvc.perform(get("/api/test/ok").header("X-Trace-Id", "first_trace", "second_trace"))
            .andReturn()
            .getResponse()
            .getHeader("X-Trace-Id");
    assertTrue(TraceContext.valid(first));
    assertTrue(TraceContext.valid(second));
    assertNotEquals(first, second);
    assertNotEquals("first_trace", second);
    assertNotEquals("second_trace", second);
    assertNull(MDC.get("traceId"));
  }

  @Test
  void routeTemplateHidesShareTokensAndQueryContents() throws Exception {
    mvc.perform(
            get("/api/community/share/PRIVATE_SHARE_TOKEN")
                .queryParam("q", "private_search")
                .header("X-Trace-Id", "route_trace_2026"))
        .andExpect(status().isOk());
    String log = http().get(0).getFormattedMessage();
    assertTrue(log.contains("route=/api/community/share/{token}"));
    assertFalse(log.contains("PRIVATE_SHARE_TOKEN"));
    assertFalse(log.contains("private_search"));
  }

  @Test
  void businessStatusesAndRetryAfterRemainUnchanged() throws Exception {
    for (int code : List.of(401, 403, 503))
      mvc.perform(get("/api/test/status/" + code))
          .andExpect(status().is(code))
          .andExpect(jsonPath("$.message").value("安全业务提示"))
          .andExpect(header().exists("X-Trace-Id"));
    mvc.perform(get("/api/test/rate"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "15"));
    assertTrue(capture.list.stream().noneMatch(e -> e.getLevel() == Level.ERROR));
    assertEquals(4, http().size());
  }

  @Test
  void concurrentRequestsDoNotShareThreadLocalIdentifiers() throws Exception {
    var executor = Executors.newFixedThreadPool(4);
    try {
      List<Future<?>> jobs = new ArrayList<>();
      for (int n = 0; n < 12; n++) {
        String id = "parallel_trace_" + n;
        jobs.add(
            executor.submit(
                () -> {
                  try {
                    mvc.perform(get("/api/test/ok").header("X-Trace-Id", id))
                        .andExpect(header().string("X-Trace-Id", id));
                    assertNull(MDC.get("traceId"));
                  } catch (Exception e) {
                    throw new RuntimeException(e);
                  }
                }));
      }
      for (var job : jobs) job.get(5, TimeUnit.SECONDS);
      assertEquals(
          12, http().stream().map(e -> e.getMDCPropertyMap().get("traceId")).distinct().count());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void executorContextIsCapturedAndRestoredEvenAfterFailure() throws Exception {
    var executor = Executors.newSingleThreadExecutor();
    try {
      executor.submit(() -> MDC.put("workerMarker", "kept")).get();
      MDC.put("traceId", "source_trace_1");
      var first =
          TraceContext.task(
              "task_one",
              () -> {
                assertEquals("source_trace_1", MDC.get("traceId"));
                assertEquals("task_one", MDC.get("taskId"));
                assertNull(MDC.get("workerMarker"));
              });
      MDC.put("traceId", "source_trace_2");
      var second =
          TraceContext.task(
              "task_two",
              () -> {
                assertEquals("source_trace_2", MDC.get("traceId"));
                throw new IllegalStateException("fixture");
              });
      executor.submit(first).get();
      assertThrows(ExecutionException.class, () -> executor.submit(second).get());
      executor
          .submit(
              () -> {
                assertNull(MDC.get("traceId"));
                assertNull(MDC.get("taskId"));
                assertEquals("kept", MDC.get("workerMarker"));
              })
          .get();
      assertEquals("source_trace_2", MDC.get("traceId"));
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void sseCompletionIsRecordedOnceOutsideHttpSlowRequestStatistics() throws Exception {
    var properties = new ObservabilityProperties();
    properties.setSlowRequestMillis(1);
    var filter = new TraceIdFilter(properties);
    var req = new MockHttpServletRequest("GET", "/api/ai/tasks/private-task/events");
    req.setAsyncSupported(true);
    req.addHeader("X-Trace-Id", "stream_trace_2026");
    var response = new MockHttpServletResponse();
    filter.doFilter(
        req,
        response,
        (r, s) -> {
          req.setAttribute(
              HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/ai/tasks/{id}/events");
          req.startAsync(req, response);
        });
    var async = (MockAsyncContext) req.getAsyncContext();
    assertNull(MDC.get("traceId"));
    Thread.sleep(10);
    var event = new AsyncEvent(async, req, response);
    for (var listener : async.getListeners()) listener.onComplete(event);
    for (var listener : async.getListeners()) listener.onComplete(event);
    assertTrue(http().isEmpty());
    var streams =
        capture.list.stream()
            .filter(e -> e.getFormattedMessage().startsWith("SSE connection finished"))
            .toList();
    assertEquals(1, streams.size());
    assertEquals(Level.INFO, streams.get(0).getLevel());
    assertEquals("stream_trace_2026", streams.get(0).getMDCPropertyMap().get("traceId"));
    assertNull(MDC.get("traceId"));
  }
}
