package com.smarttrip.service;

import static org.junit.jupiter.api.Assertions.*;

import com.smarttrip.config.AiProperties;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

/** Local configuration and error classification only; no model or draft-generation verification. */
class AiReadinessTests {
  @Test
  void readinessReportsMissingConfigurationAndRecoversWithoutCallingProvider() {
    var requests = new AtomicInteger();
    var client = new OkHttpClient.Builder().addInterceptor(chain -> {
      requests.incrementAndGet();
      throw new AssertionError("Readiness must not call a model");
    }).build();
    var config = new AiProperties();
    var provider = new AiProvider(config, client);
    assertState(provider, "DISABLED");
    config.setEnabled(true);
    assertState(provider, "QUOTA_UNVERIFIED");
    config.setFreeQuotaVerified(true);
    assertState(provider, "KEY_MISSING");
    config.setApiKey("YOUR_API_KEY");
    assertState(provider, "KEY_MISSING");
    config.setApiKey("invalid key");
    assertState(provider, "KEY_INVALID");
    config.setApiKey("fixture-local-only-key");
    config.setModel("");
    assertState(provider, "MODEL_MISSING");
    config.setModel("qwen3.8-flash");
    for (String url : List.of("not-a-url", "https://user:secret@fixture.invalid/v1", "https://fixture.invalid/v1?key=secret")) {
      config.setBaseUrl(url);
      assertState(provider, "BASE_URL_INVALID");
    }
    config.setBaseUrl("https://fixture.invalid/compatible-mode/v1");
    for (int limit : new int[] {0, 65537}) {
      config.setMaxOutputTokens(limit);
      assertState(provider, "OUTPUT_LIMIT_INVALID");
    }
    config.setMaxOutputTokens(8192);
    assertState(provider, "READY");
    assertTrue(provider.reason().contains("未通过付费探测"));
    assertFalse((provider.reason() + provider.action()).contains(config.getApiKey()));
    assertEquals(0, requests.get());
  }

  @Test
  void providerErrorsSeparateAuthenticationModelRateQuotaAndTimeoutWithoutRawDetails() {
    record ErrorCase(int http, String code, String status, int businessCode) {}
    var cases = List.of(
        new ErrorCase(401, "invalid_api_key", "KEY_INVALID", 503),
        new ErrorCase(404, "model_not_found", "MODEL_UNAVAILABLE", 503),
        new ErrorCase(429, "", "RATE_LIMITED", 429),
        new ErrorCase(429, "Throttling.AllocationQuota", "RATE_LIMITED", 429),
        new ErrorCase(403, "AllocationQuota.FreeTierOnly", "QUOTA_EXHAUSTED", 503),
        new ErrorCase(408, "", "TIMEOUT", 504),
        new ErrorCase(504, "", "TIMEOUT", 504),
        new ErrorCase(400, "InvalidParameter", "REQUEST_REJECTED", 502),
        new ErrorCase(500, "private-provider-detail", "PROVIDER_UNAVAILABLE", 502));
    for (var item : cases) {
      assertEquals(item.status(), ProviderErrors.aiStatus(item.http(), item.code()));
      var failure = ProviderErrors.ai(item.http(), item.code());
      assertEquals(item.businessCode(), failure.getCode().intValue());
      assertFalse(failure.getMessage().contains("private-provider-detail"));
    }
  }

  @Test
  void completeJsonWrappersAreNormalizedWithoutGuessingBrokenResponses() {
    var provider = new AiProvider(new AiProperties());
    String raw = "{\"days\":[]}";
    assertEquals(raw, provider.parse("\uFEFF" + raw).json());
    assertEquals(raw, provider.parse("```json\n" + raw + "\n```").json());
    for (String invalid : List.of("```json\n" + raw, raw + " {}", "说明 " + raw, "{}", "[]"))
      assertEquals(422, assertThrows(com.smarttrip.common.BusinessException.class,
          () -> provider.parse(invalid)).getCode().intValue());
  }

  private void assertState(AiProvider provider, String status) {
    assertEquals(status, provider.status());
    assertEquals("READY".equals(status), provider.available());
    assertEquals("READY".equals(status), provider.configurationProblems().isEmpty());
    assertFalse(provider.reason().isBlank());
    assertFalse(provider.action().isBlank());
  }
}
