package com.smarttrip.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.smarttrip.common.BusinessException;
import com.smarttrip.config.RagProperties;
import com.smarttrip.entity.TravelRequirement;
import com.smarttrip.rag.knowledge.ElasticsearchKnowledgeClient;
import com.smarttrip.rag.knowledge.KnowledgeIndexService;
import com.smarttrip.rag.knowledge.RagException;
import com.smarttrip.service.TravelRequirementService;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** No index mutations, task creation, quota reservation or ChatModel invocation. */
@Service
public class RagDiagnosticsService {
  private static final Logger LOG = LoggerFactory.getLogger(RagDiagnosticsService.class);
  private final RagProperties properties;
  private final ElasticsearchKnowledgeClient elasticsearch;
  private final KnowledgeIndexService index;
  private final TravelRetrievalQueryBuilder queries;
  private final TravelRagService rag;
  private final TravelRequirementService requirements;

  public RagDiagnosticsService(RagProperties properties, ElasticsearchKnowledgeClient elasticsearch,
      KnowledgeIndexService index, TravelRetrievalQueryBuilder queries, TravelRagService rag,
      TravelRequirementService requirements) {
    this.properties = properties;
    this.elasticsearch = elasticsearch;
    this.index = index;
    this.queries = queries;
    this.rag = rag;
    this.requirements = requirements;
  }

  /** The default response is configuration-only. An explicit probe reads ES, not Ollama. */
  public Map<String, Object> status(boolean probe) {
    long started = System.nanoTime();
    var result = new LinkedHashMap<String, Object>();
    result.put("ragEnabled", properties.isEnabled());
    result.put("fallbackToOriginal", properties.isFallbackToOriginal());
    result.put("rerankEnabled", properties.isRerankEnabled());
    result.put("indexAlias", properties.getIndexName());
    result.put("embeddingModel", properties.getEmbeddingModel());
    result.put("embeddingVersion", properties.getEmbeddingVersion());
    result.put("probeRequested", probe);
    result.put("indexStatus", "NOT_CHECKED");
    result.put("embeddingStatus", "NOT_CHECKED");
    result.put("mappedVectorDimensions", null);
    result.put("publicChunkCount", null);
    result.put("publicDocumentCountEstimate", null);
    result.put("countsScope", "AUTHORIZED_PUBLIC_CURRENT_EMBEDDING_VERSION");
    result.put("documentCountIsEstimate", true);
    result.put("failureReason", "");
    result.put("chatModelCalled", false);
    if (!probe) return result;
    try {
      var physical = index.currentIndices();
      result.put("physicalIndices", physical);
      if (physical.isEmpty()) {
        result.put("indexStatus", "INDEX_EMPTY");
        result.put("failureReason", "INDEX_EMPTY");
        result.put("message", hint("INDEX_EMPTY"));
      } else {
        result.put("mappedVectorDimensions", elasticsearch.mappedDimensions());
        // Count only the same authorized public scope used by both retrieval branches.
        var response = elasticsearch.search(Map.of(
            "size", 0, "track_total_hits", true,
            "query", Map.of("bool", Map.of("filter", queries.accessFilters())),
            "aggs", Map.of("documents", Map.of("cardinality",
                Map.of("field", "documentId", "precision_threshold", 40000)))));
        JsonNode total = response == null ? null : response.path("hits").path("total");
        if (total != null && total.isObject()) {
          if (!"eq".equals(total.path("relation").asText()))
            throw new RagException("ES_OUTPUT_INVALID", "公开知识计数未完整返回");
          total = total.path("value");
        }
        JsonNode documents = response == null ? null
            : response.path("aggregations").path("documents").path("value");
        long chunks = count(total);
        long documentEstimate = count(documents);
        result.put("publicChunkCount", chunks);
        result.put("publicDocumentCountEstimate", documentEstimate);
        result.put("indexStatus", chunks == 0 ? "INDEX_EMPTY" : "READY");
        if (chunks == 0) {
          result.put("failureReason", "INDEX_EMPTY");
          result.put("message", "映射已就绪，但当前模型版本下没有允许检索的公开知识，请核对导入清单与权限");
        } else {
          result.put("message", "索引映射及公开知识计数已读取；Embedding 和生成链路尚未由此操作验证");
        }
      }
    } catch (RagException ex) {
      result.put("indexStatus", "UNAVAILABLE");
      result.put("failureReason", ex.code());
      result.put("message", hint(ex.code()));
    } catch (RuntimeException ex) {
      result.put("indexStatus", "UNAVAILABLE");
      result.put("failureReason", "RAG_INTERNAL_ERROR");
      result.put("message", "RAG 状态读取失败，请按 traceId 检查服务日志");
      LOG.warn("rag_status failureType={}", ex.getClass().getSimpleName());
    }
    result.put("durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    LOG.info("rag_status indexStatus={} failureReason={} durationMs={}",
        result.get("indexStatus"), result.get("failureReason"), result.get("durationMs"));
    return result;
  }

  /** Reuse the generation retrieval pipeline, while leaving tasks and paid models untouched. */
  public Map<String, Object> preview(TravelRequirement requirement) {
    var review = requirements.review(requirement, false);
    var normalized = (TravelRequirement) review.get("requirement");
    if (normalized.destinations() == null || normalized.destinations().isEmpty())
      throw new BusinessException(400, "检索预览需要结构化目的地，不会自动调用模型提取描述");
    try {
      var result = new LinkedHashMap<>(rag.preview(normalized, "preview-" + UUID.randomUUID()));
      result.put("requirement", normalized);
      result.put("clarifications", review.get("clarifications"));
      result.put("conflicts", review.get("conflicts"));
      result.put("requirementsConfirmed", false);
      return result;
    } catch (RagException ex) {
      throw new BusinessException(503, "旅行知识检索失败（" + ex.code() + "）：" + hint(ex.code()));
    }
  }

  private long count(JsonNode value) {
    if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0)
      throw new RagException("ES_OUTPUT_INVALID", "公开知识计数响应无效");
    return value.longValue();
  }

  private String hint(String code) {
    return switch (code) {
      case "INDEX_EMPTY" -> "知识读取别名尚未初始化或公开知识范围为空，请由受信任维护人员检查现有 init/import 流程";
      case "ES_AUTH_FAILED" -> "知识索引鉴权失败，请核对现有账号与密码配置，不要重置密码";
      case "ES_UNAVAILABLE" -> "Elasticsearch 连接或响应异常，请核对服务地址及连接状态";
      case "EMBEDDING_MODEL_MISMATCH" -> "索引与当前 Embedding 模型/版本不同，需要受控全量重建";
      case "DIMENSION_MISMATCH" -> "实际向量维度与索引映射不一致，请核对模型版本后受控重建";
      case "OLLAMA_UNAVAILABLE" -> "本地 Embedding 服务不可用，请核对 Ollama 地址及状态";
      case "EMBEDDING_MODEL_MISSING" -> "配置的本地 Embedding 模型不存在，请核对已安装模型名称";
      case "RETRIEVAL_TIMEOUT" -> "知识检索或 Embedding 请求超时，请核对服务负载及相应超时配置";
      case "RAG_CONFIG_INVALID" -> "RAG 配置不完整或无效，请核对索引名称、地址及成对配置的凭据";
      case "INDEX_ALIAS_INVALID" -> "知识别名指向多个实体索引，需维护人员核对发布与恢复状态";
      case "ES_OUTPUT_INVALID" -> "索引响应不符合预期，未将异常响应视为成功或空知识库";
      default -> "请检查 RAG 结构化日志中的同名失败原因；未创建任务或调用生成模型";
    };
  }
}
