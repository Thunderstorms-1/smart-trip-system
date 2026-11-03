package com.smarttrip.rag;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.smarttrip.config.RagProperties;
import com.smarttrip.rag.evaluation.*;
import com.smarttrip.rag.knowledge.RagException;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.*;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;

/** A subprocess verifies command exit without terminating the test JVM or using real retrieval. */
class RagEvaluationCommandTests {
  @Test void simultaneousKnowledgeAndEvaluationCommandsAreRejectedBeforeExecution() {
    var evaluation = mock(RagEvaluationService.class);
    var context = mock(ConfigurableApplicationContext.class);
    var ex = assertThrows(RagException.class, () ->
        new RagEvaluationRunner(evaluation, "cases.json", "report.json", true, context));
    assertEquals("ADMIN_COMMAND_CONFLICT", ex.code());
    verifyNoInteractions(evaluation, context);
  }

  @Test void evaluationCommandWritesReportAndExitsBeforeApplicationReady(@TempDir Path tmp) throws Exception {
    Path report = tmp.resolve("lifecycle-only-report.json"), marker = tmp.resolve("business-ready-marker");
    String binary = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
    String java = Path.of(System.getProperty("java.home"), "bin", binary).toString();
    var child = new ProcessBuilder(java, "-Xmx96m", "-cp", System.getProperty("java.class.path"),
        ProbeMain.class.getName(), report.toString(), marker.toString())
        .redirectErrorStream(true).redirectOutput(tmp.resolve("command-output.log").toFile()).start();
    boolean finished = child.waitFor(30, TimeUnit.SECONDS);
    if (!finished) child.destroyForcibly();
    assertTrue(finished, "Evaluation command must terminate");
    assertEquals(0, child.exitValue(), Files.readString(tmp.resolve("command-output.log")));
    assertTrue(Files.readString(report).contains("LIFECYCLE_FIXTURE_ONLY"));
    assertFalse(Files.exists(marker), "ApplicationReadyEvent must not run normal business task recovery");
  }

  public static class ProbeMain {
    public static void main(String[] args) {
      var app = new SpringApplication(ProbeConfiguration.class);
      app.setWebApplicationType(WebApplicationType.NONE);
      app.setDefaultProperties(Map.of("probe.report", args[0], "probe.marker", args[1],
          "spring.main.banner-mode", "off"));
      app.run();
    }
  }
  @Configuration(proxyBeanMethods = false)
  public static class ProbeConfiguration {
    @Bean RagEvaluationService evaluation() {
      return new RagEvaluationService(null, null, null, new RagProperties()) {
        @Override public Map<String, Object> evaluate(Path ignored) {
          return Map.of("measurement", "LIFECYCLE_FIXTURE_ONLY");
        }
      };
    }
    @Bean RagEvaluationRunner command(RagEvaluationService evaluation,
        ConfigurableApplicationContext context, Environment environment) {
      return new RagEvaluationRunner(evaluation, "unused-fixture.json",
          environment.getRequiredProperty("probe.report"), false, context);
    }
    @Bean ReadyMarker marker(Environment environment) {
      return new ReadyMarker(Path.of(environment.getRequiredProperty("probe.marker")));
    }
  }
  public static class ReadyMarker {
    private final Path target;
    public ReadyMarker(Path target) { this.target = target; }
    @EventListener(ApplicationReadyEvent.class)
    public void ready() throws Exception { Files.writeString(target, "business task recovery would run"); }
  }
}
