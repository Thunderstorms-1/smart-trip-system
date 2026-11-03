package com.smarttrip.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smarttrip.config.RagProperties;
import com.smarttrip.rag.knowledge.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RagKnowledgeIndexTests {
  @Test void aliasRecoveryMustBeAcknowledgedBeforeStagingCanBeDiscarded() throws Exception {
    var client=mock(ElasticsearchKnowledgeClient.class);
    when(client.indexName()).thenReturn("travel-alias");
    var service=new KnowledgeIndexService(client,new RagProperties());
    for(String response:List.of("{\"acknowledged\":false}","{\"acknowledged\":true,\"errors\":true}","{}")) {
      when(client.request(eq("POST"),eq("/_aliases"),any(),anyInt())).thenReturn(new ObjectMapper().readTree(response));
      assertEquals("INDEX_RECOVERY_REQUIRED",assertThrows(RagException.class,()->service.restore(List.of("old-index"),"new-index")).code());
    }
  }
  @Test void confirmedRecoveryRestoresOldReadAliasAndRemovesNewAliasInOneRequest() throws Exception {
    var client=mock(ElasticsearchKnowledgeClient.class);
    when(client.indexName()).thenReturn("travel-alias");
    var properties=new RagProperties();
    when(client.request(eq("POST"),eq("/_aliases"),any(),anyInt())).thenReturn(new ObjectMapper().readTree("{\"acknowledged\":true,\"errors\":false}"));
    assertDoesNotThrow(()->new KnowledgeIndexService(client,properties).restore(List.of("old-index"),"new-index"));
    verify(client).request("POST","/_aliases",Map.of("actions",List.of(
        Map.of("remove",Map.of("index","new-index","alias","travel-alias","must_exist",false)),
        Map.of("add",Map.of("index","old-index","alias","travel-alias","is_write_index",false)))),properties.getIngestionTimeoutMillis());
  }
}
