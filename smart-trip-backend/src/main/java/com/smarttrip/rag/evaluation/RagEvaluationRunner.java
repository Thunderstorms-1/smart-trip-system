package com.smarttrip.rag.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smarttrip.rag.knowledge.RagException;
import java.nio.file.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Explicit offline/admin startup command; exits before business ApplicationReady listeners. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name = "smarttrip.rag.evaluate", havingValue = "true")
public class RagEvaluationRunner implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(RagEvaluationRunner.class);
  private final RagEvaluationService evaluation;
  private final String dataset;
  private final String reportPath;
  private final ConfigurableApplicationContext context;
  public RagEvaluationRunner(RagEvaluationService evaluation,
      @Value("${smarttrip.rag.evaluation-dataset:../knowledge/travel-rag/evaluation/cases.json}") String dataset,
      @Value("${smarttrip.rag.evaluation-report:target/rag-evaluation-report.json}") String reportPath,
      @Value("${smarttrip.rag.admin-enabled:false}") boolean knowledgeCommandEnabled,
      ConfigurableApplicationContext context) {
    // Constructor validation runs before any ApplicationRunner, including the knowledge command.
    if (knowledgeCommandEnabled) throw new RagException("ADMIN_COMMAND_CONFLICT", "知识管理命令与检索评测命令不能同时启用");
    this.evaluation = evaluation; this.dataset = dataset; this.reportPath = reportPath; this.context = context;
  }
  @Override public void run(ApplicationArguments arguments) throws Exception {
    var report = evaluation.evaluate(Path.of(dataset));
    Path target = Path.of(reportPath).toAbsolutePath();
    Files.createDirectories(target.getParent());
    Files.writeString(target, new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report));
    log.info("RAG retrieval evaluation written to {} (no ChatModel invoked)", target);
    // Never reach ApplicationReadyEvent: normal AI-task recovery may submit paid provider work.
    System.exit(SpringApplication.exit(context));
  }
}
