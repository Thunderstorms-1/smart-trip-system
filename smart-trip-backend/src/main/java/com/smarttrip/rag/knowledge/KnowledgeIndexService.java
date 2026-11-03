package com.smarttrip.rag.knowledge;

import com.fasterxml.jackson.databind.*;
import com.smarttrip.config.RagProperties;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeIndexService {
  private final ElasticsearchKnowledgeClient client;
  private final RagProperties config;
  private final ObjectMapper json = new ObjectMapper();
  public KnowledgeIndexService(ElasticsearchKnowledgeClient client, RagProperties config) { this.client=client; this.config=config; }
  public List<String> currentIndices() {
    try {
      JsonNode response=client.request("GET", "/_alias/"+client.indexName(),null);
      List<String> names=new ArrayList<>(); response.fieldNames().forEachRemaining(names::add);
      if (names.size()>1) throw new RagException("INDEX_ALIAS_INVALID","知识读取别名指向多个索引，拒绝管理操作");
      return names;
    } catch (RagException e) { if ("INDEX_EMPTY".equals(e.code())) return List.of(); throw e; }
  }
  public String createStaging(int dimensions) {
    if (dimensions<1 || dimensions>4096) throw new RagException("DIMENSION_MISMATCH","Embedding维度无效");
    String index=client.indexName()+"-g-"+UUID.randomUUID().toString().replace("-","");
    client.request("PUT","/"+index,mapping(dimensions),config.getIngestionTimeoutMillis());
    return index;
  }
  public Map<String,Object> mapping(int dimensions) {
    Map<String,Object> fields=new LinkedHashMap<>();
    for(String name:List.of("documentId","chunkId","destinations","regions","category","applicableSeasons","sourceName","sourceUrl",
        "visibility","documentVersion","contentHash","embeddingModel","embeddingVersion")) fields.put(name,Map.of("type","keyword"));
    fields.put("title",Map.of("type","text","analyzer","cjk"));
    fields.put("content",Map.of("type","text","analyzer","cjk"));
    fields.put("sourceUpdatedAt",Map.of("type","date","format","strict_date_optional_time||strict_date"));
    for (String name : List.of("validFrom", "validUntil")) fields.put(name, Map.of("type", "date", "format", "strict_date"));
    fields.put("ingestedAt",Map.of("type","date","format","strict_date_optional_time||strict_date"));
    fields.put("ownerId",Map.of("type","long")); fields.put("authorized",Map.of("type","boolean")); fields.put("testOnly",Map.of("type","boolean"));
    fields.put("embedding",Map.of("type","dense_vector","dims",dimensions,"index",true,"similarity","cosine",
        "index_options",Map.of("type","hnsw","m",16,"ef_construction",100)));
    return Map.of("settings",Map.of("number_of_shards",1,"number_of_replicas",0),"mappings",Map.of("dynamic","strict",
        "_meta",Map.of("embeddingModel",config.getEmbeddingModel(),"embeddingVersion",config.getEmbeddingVersion(),"vectorDimensions",dimensions),"properties",fields));
  }
  public int copyUnchanged(List<String> oldIndices, String staging, Collection<String> replacedDocumentIds) {
    if(oldIndices.isEmpty()) return 0;
    List<Object> mustNot=new ArrayList<>(); mustNot.add(Map.of("exists",Map.of("field","ownerId")));
    if(!replacedDocumentIds.isEmpty()) mustNot.add(Map.of("terms",Map.of("documentId",replacedDocumentIds)));
    Object query=Map.of("bool",Map.of("filter",List.of(Map.of("term",Map.of("visibility","PUBLIC")),Map.of("term",Map.of("authorized",true))),"must_not",mustNot));
    JsonNode result=client.request("POST","/_reindex?wait_for_completion=true&refresh=true",Map.of("source",Map.of("index",oldIndices.get(0),"query",query),"dest",Map.of("index",staging)),config.getIngestionTimeoutMillis());
    if(result.path("timed_out").asBoolean() || result.path("failures").size()>0 || result.path("version_conflicts").asInt()>0)
      throw new RagException("INDEX_WRITE_FAILED","旧知识快照复制未完整完成，保留旧索引");
    if(result.path("created").asInt()+result.path("updated").asInt()!=result.path("total").asInt()) throw new RagException("INDEX_WRITE_FAILED","知识复制数量不一致");
    return result.path("total").asInt();
  }
  public void putChunk(String staging, KnowledgeChunk chunk, float[] vector, int dimensions) {
    if(vector.length!=dimensions) throw new RagException("DIMENSION_MISMATCH","导入过程中Embedding向量维度改变");
    var body=json.valueToTree(chunk);
    ((com.fasterxml.jackson.databind.node.ObjectNode)body).set("embedding",json.valueToTree(vector));
    client.request("PUT","/"+staging+"/_doc/"+chunk.chunkId(),body,config.getIngestionTimeoutMillis());
  }
  public void refresh(String index) { client.request("POST","/"+index+"/_refresh",null,config.getIngestionTimeoutMillis()); }
  public int count(String index) {
    return client.request("GET","/"+index+"/_count",null,config.getIngestionTimeoutMillis()).path("count").asInt();
  }
  public void publish(List<String> previous, String staging) {
    client.request("POST","/"+staging+"/_refresh",null,config.getIngestionTimeoutMillis());
    List<Object> actions=new ArrayList<>();
    for(String old:previous) actions.add(Map.of("remove",Map.of("index",old,"alias",client.indexName(),"must_exist",true)));
    actions.add(Map.of("add",Map.of("index",staging,"alias",client.indexName(),"is_write_index",false)));
    JsonNode result=client.request("POST","/_aliases",Map.of("actions",actions),config.getIngestionTimeoutMillis());
    if(!result.path("acknowledged").asBoolean() || result.path("errors").asBoolean()) throw new RagException("INDEX_WRITE_FAILED","知识别名切换未确认");
  }
  public void restore(List<String> previous,String staging) {
    List<Object> actions=new ArrayList<>();
    actions.add(Map.of("remove",Map.of("index",staging,"alias",client.indexName(),"must_exist",false)));
    for(String old:previous) actions.add(Map.of("add",Map.of("index",old,"alias",client.indexName(),"is_write_index",false)));
    JsonNode result=client.request("POST","/_aliases",Map.of("actions",actions),config.getIngestionTimeoutMillis());
    if(!result.path("acknowledged").asBoolean() || result.path("errors").asBoolean())
      throw new RagException("INDEX_RECOVERY_REQUIRED","旧知识别名恢复未确认，保留新旧快照供人工核对");
  }
  public void discard(String staging) {
    if(staging!=null && staging.matches(java.util.regex.Pattern.quote(client.indexName())+"-g-[0-9a-f]{32}"))
      client.request("DELETE","/"+staging,null,config.getIngestionTimeoutMillis());
  }
}