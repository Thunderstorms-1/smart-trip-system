package com.smarttrip.controller;

import com.smarttrip.common.*;
import com.smarttrip.service.*;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class TaskEventsController {
  private final AiTaskStore store;
  private final WorkbenchService trips;
  private final ScheduledExecutorService events;
  private final AtomicInteger active = new AtomicInteger();

  @org.springframework.beans.factory.annotation.Autowired
  public TaskEventsController(AiTaskStore store, WorkbenchService trips) {
    this(
        store,
        trips,
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "task-events");
              t.setDaemon(true);
              return t;
            }));
  }

  TaskEventsController(AiTaskStore store, WorkbenchService trips, ScheduledExecutorService events) {
    this.store = store;
    this.trips = trips;
    this.events = events;
  }

  @PreDestroy
  public void close() {
    events.shutdownNow();
  }

  @GetMapping(value = "/api/ai/tasks/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter events(@PathVariable String id) {
    store.get(id);
    Long user = UserContext.getUserId();
    if (active.incrementAndGet() > 32) {
      active.decrementAndGet();
      throw new BusinessException(429, "任务连接过多，请稍后重试");
    }
    SseEmitter emitter = new SseEmitter(180000L);
    AtomicBoolean closed = new AtomicBoolean();
    AtomicReference<ScheduledFuture<?>> future = new AtomicReference<>();
    Runnable cleanup =
        () -> {
          if (closed.compareAndSet(false, true)) {
            active.decrementAndGet();
            if (future.get() != null) future.get().cancel(false);
          }
        };
    emitter.onCompletion(cleanup);
    emitter.onTimeout(
        () -> {
          cleanup.run();
          emitter.complete();
        });
    emitter.onError(e -> cleanup.run());
    future.set(
        events.scheduleAtFixedRate(
            TraceContext.task(
                id,
                () -> {
                  if (closed.get()) return;
                  UserContext.setUserId(user);
                  try {
                    var t = store.get(id);
                    String status = (String) t.get("status");
                    emitter.send(
                        SseEmitter.event()
                            .name("task")
                            .id(id)
                            .data(trips.encode(t), MediaType.APPLICATION_JSON));
                    if (!Set.of("QUEUED", "RUNNING").contains(status)) {
                      cleanup.run();
                      emitter.complete();
                    }
                  } catch (Exception e) {
                    cleanup.run();
                    emitter.completeWithError(e);
                  } finally {
                    UserContext.clear();
                  }
                }),
            0,
            1,
            TimeUnit.SECONDS));
    if (closed.get()) future.get().cancel(false);
    return emitter;
  }
}
