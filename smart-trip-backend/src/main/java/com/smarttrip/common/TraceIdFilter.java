package com.smarttrip.config;

import com.smarttrip.common.TraceContext;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {
  public static final String HEADER = "X-Trace-Id";
  private static final String TRACE_ATTRIBUTE = TraceIdFilter.class.getName() + ".trace";
  private static final String TIMING_ATTRIBUTE = TraceIdFilter.class.getName() + ".timing";
  private static final Logger LOG = LoggerFactory.getLogger(TraceIdFilter.class);
  private final ObservabilityProperties properties;

  public TraceIdFilter(ObservabilityProperties properties) {
    this.properties = properties;
  }

  @Override
  protected boolean shouldNotFilterAsyncDispatch() {
    return false;
  }

  @Override
  protected boolean shouldNotFilterErrorDispatch() {
    return false;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String trace = (String) request.getAttribute(TRACE_ATTRIBUTE);
    if (trace == null) {
      var headers = Collections.list(request.getHeaders(HEADER));
      trace = TraceContext.normalize(headers.size() == 1 ? headers.get(0) : null);
      request.setAttribute(TRACE_ATTRIBUTE, trace);
    }
    response.setHeader(HEADER, trace);
    Timing timing = (Timing) request.getAttribute(TIMING_ATTRIBUTE);
    if (timing == null) {
      timing = new Timing(trace, request.getMethod(), properties);
      request.setAttribute(TIMING_ATTRIBUTE, timing);
    }
    boolean failed = false;
    try (var scope = TraceContext.open(trace, null)) {
      try {
        chain.doFilter(request, response);
      } catch (IOException | ServletException | RuntimeException failure) {
        failed = true;
        LOG.error(
            "Unhandled request failure method={} route={}",
            request.getMethod(),
            route(request),
            failure);
        throw failure;
      } finally {
        timing.route = route(request);
        if (request.isAsyncStarted()) timing.listen(request, response);
        else timing.complete(request, response, failed);
      }
    }
  }

  private static String route(HttpServletRequest request) {
    Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
    return pattern == null ? "UNMATCHED" : pattern.toString();
  }

  private static final class Timing {
    private final String trace, method;
    private final long started = System.nanoTime();
    private final ObservabilityProperties properties;
    private final AtomicBoolean completed = new AtomicBoolean(), listening = new AtomicBoolean();
    private volatile String route = "UNMATCHED", outcome = "COMPLETED";
    private boolean stream;

    Timing(String trace, String method, ObservabilityProperties properties) {
      this.trace = trace;
      this.method = method;
      this.properties = properties;
    }

    void listen(HttpServletRequest request, HttpServletResponse response) {
      stream = route.endsWith("/events");
      if (!listening.compareAndSet(false, true)) return;
      try {
        request
            .getAsyncContext()
            .addListener(
                new AsyncListener() {
                  @Override
                  public void onComplete(AsyncEvent event) {
                    complete(request, response, false);
                  }

                  @Override
                  public void onTimeout(AsyncEvent event) {
                    outcome = "TIMEOUT";
                  }

                  @Override
                  public void onError(AsyncEvent event) {
                    outcome = "ERROR";
                  }

                  @Override
                  public void onStartAsync(AsyncEvent event) {
                    event.getAsyncContext().addListener(this);
                  }
                });
      } catch (IllegalStateException alreadyCompleted) {
        complete(request, response, false);
      }
    }

    void complete(HttpServletRequest request, HttpServletResponse response, boolean failed) {
      if (!completed.compareAndSet(false, true) || !properties.isRequestTimingEnabled()) return;
      String path = request.getRequestURI().substring(request.getContextPath().length());
      if (!path.startsWith("/api/")) return;
      int status = failed ? 500 : response.getStatus();
      if (failed) outcome = "ERROR";
      long millis = (System.nanoTime() - started) / 1_000_000;
      try (var scope = TraceContext.open(trace, null)) {
        if (stream)
          LOG.info(
              "SSE connection finished method={} route={} status={} outcome={} durationMs={}",
              method,
              route,
              status,
              outcome,
              millis);
        else if (millis >= properties.getSlowRequestMillis() || status >= 500)
          LOG.warn(
              "HTTP request finished method={} route={} status={} outcome={} durationMs={}",
              method,
              route,
              status,
              outcome,
              millis);
        else
          LOG.info(
              "HTTP request finished method={} route={} status={} outcome={} durationMs={}",
              method,
              route,
              status,
              outcome,
              millis);
      }
    }
  }
}
