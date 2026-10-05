package com.smarttrip;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smarttrip.common.BusinessException;
import com.smarttrip.config.AiProperties;
import com.smarttrip.service.AiProvider;
import java.io.*;
import org.junit.jupiter.api.Test;

class AiProviderTests {
  @Test
  void fragmentedContentAndUnicodeAreJoined() throws Exception {
    String wire =
        "data: {\"choices\":[{\"delta\":{\"content\":\"成\"},\"finish_reason\":null}]}\n\n"
            + "data: {\"choices\":[{\"delta\":{\"content\":\"都\"},\"finish_reason\":\"stop\"}]}\n\n"
            + "data: [DONE]\n\n";
    assertEquals(
        "成都", AiProvider.readSse(new BufferedReader(new StringReader(wire)), new ObjectMapper()));
  }

  @Test
  void interruptedResponseIsRejected() {
    assertThrows(
        IOException.class,
        () ->
            AiProvider.readSse(
                new BufferedReader(new StringReader("data: {\"choices\":[]}\n\n")),
                new ObjectMapper()));
  }

  @Test
  void lengthLimitDoesNotSavePartialResponse() {
    String wire =
        "data:"
            + " {\"choices\":[{\"delta\":{\"content\":\"partial\"},\"finish_reason\":\"length\"}]}\n\n"
            + "data: [DONE]\n\n";
    assertThrows(
        IOException.class,
        () -> AiProvider.readSse(new BufferedReader(new StringReader(wire)), new ObjectMapper()));
  }

  @Test
  void disabledFreeGateNeverCallsProvider() {
    AiProperties p = new AiProperties();
    p.setApiKey("test-only");
    p.setEnabled(true);
    p.setFreeQuotaVerified(false);
    AiProvider provider = new AiProvider(p);
    assertFalse(provider.available());
    assertEquals("QUOTA_UNVERIFIED", provider.status());
    assertEquals(
        503, assertThrows(BusinessException.class, () -> provider.complete("成都")).getCode());
  }

  @Test
  void nonObjectOrMissingDaysIsRejected() {
    AiProvider p = new AiProvider(new AiProperties());
    assertThrows(BusinessException.class, () -> p.parse("[]"));
    assertThrows(BusinessException.class, () -> p.parse("{}"));
  }
}
