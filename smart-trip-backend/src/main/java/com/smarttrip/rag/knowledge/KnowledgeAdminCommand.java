package com.smarttrip.rag.knowledge;

import java.nio.file.Path;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
@Profile("rag-admin")
@ConditionalOnProperty(prefix="smarttrip.rag",name="admin-enabled",havingValue="true")
public class KnowledgeAdminCommand implements ApplicationRunner {
  private final KnowledgeIngestionService ingestion;
  private final Environment environment;
  private final ConfigurableApplicationContext context;
  private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(KnowledgeAdminCommand.class);
  public KnowledgeAdminCommand(KnowledgeIngestionService ingestion,Environment environment,ConfigurableApplicationContext context) {
    this.ingestion=ingestion; this.environment=environment; this.context=context;
  }
  public void run(ApplicationArguments arguments) {
    String command=environment.getProperty("smarttrip.rag.command","");
    switch(command) {
      case "inspect" -> {
        var documents=ingestion.inspect(manifest());
        LOG.info("rag_inspect documents={} chunks={}",documents.size(),documents.stream().mapToInt(d->d.chunks().size()).sum());
        documents.forEach(d->LOG.info("rag_document documentId={} chunks={} testOnly={} sourceDateKnown={}",
            d.document().documentId(),d.chunks().size(),d.document().testOnly(),d.document().sourceUpdatedAt()!=null));
      }
      case "init" -> LOG.info("rag_admin {}",ingestion.initialize());
      case "import" -> LOG.info("rag_admin {}",ingestion.importManifest(manifest(),false));
      case "rebuild" -> LOG.info("rag_admin {}",ingestion.importManifest(manifest(),true));
      case "delete" -> LOG.info("rag_admin {}",ingestion.deleteDocument(environment.getProperty("smarttrip.rag.document-id")));
      default -> throw new RagException("ADMIN_COMMAND_INVALID","受控知识命令应为inspect/init/import/rebuild/delete");
    }
    // Exit before ApplicationReadyEvent: an offline knowledge command must not recover business AI tasks.
    System.exit(SpringApplication.exit(context));
  }
  private Path manifest() {
    String path=environment.getProperty("smarttrip.rag.manifest");
    if(path==null || path.isBlank()) throw new RagException("MANIFEST_INVALID","请明确指定知识清单路径");
    return Path.of(path);
  }
}