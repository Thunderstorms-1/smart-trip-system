package com.smarttrip.common;

import java.util.*;
import org.slf4j.MDC;

/** Capture only correlation identifiers; restore the executor's prior context on every exit. */
public final class TraceContext {
  public static final String TRACE_ID = "traceId", TASK_ID = "taskId";

  private TraceContext() {}

  public static boolean valid(String value) {
    return value != null && value.matches("[A-Za-z0-9_-]{8,64}");
  }

  public static String normalize(String value) {
    return valid(value) ? value : UUID.randomUUID().toString().replace("-", "");
  }

  public static Scope open(String trace, String task) {
    return new Scope(normalize(trace), task);
  }

  public static Runnable task(String id, Runnable action) {
    String trace = normalize(MDC.get(TRACE_ID));
    return () -> {
      try (var scope = open(trace, id)) {
        action.run();
      }
    };
  }

  public static final class Scope implements AutoCloseable {
    private final Map<String, String> previous = MDC.getCopyOfContextMap();

    private Scope(String trace, String task) {
      MDC.clear();
      MDC.put(TRACE_ID, trace);
      if (task != null && task.matches("[A-Za-z0-9_-]{1,80}")) MDC.put(TASK_ID, task);
    }

    @Override
    public void close() {
      MDC.clear();
      if (previous != null) MDC.setContextMap(previous);
    }
  }
}
