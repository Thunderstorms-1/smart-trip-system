package com.smarttrip.service;

import com.smarttrip.common.BusinessException;
import java.util.Set;

/** Upstream credentials and provider messages must never be forwarded to users. */
final class ProviderErrors {
  private ProviderErrors() {}

  static String aiStatus(int http, String code) {
    String normalized = code == null ? "" : code.toLowerCase(java.util.Locale.ROOT);
    if (Set.of("allocationquota.freetieronly", "quotaexhausted", "insufficient_quota", "quotaexceeded", "arrearage", "prepaidbilloverdue", "postpaidbilloverdue", "budgetlimitexceeded").contains(normalized))
      return "QUOTA_EXHAUSTED";
    if (http == 401 || Set.of("invalidapikey", "invalid_api_key", "invalidaccesskeyid").contains(normalized))
      return "KEY_INVALID";
    if (http == 403 || http == 404 || Set.of("modelnotfound", "model_not_found", "invalid_model", "accessdenied.unpurchased").contains(normalized))
      return "MODEL_UNAVAILABLE";
    if (http == 429 || Set.of("throttling.ratequota", "throttling.allocationquota").contains(normalized)) return "RATE_LIMITED";
    if (http == 408 || http == 504) return "TIMEOUT";
    if (http == 400 || http == 422) return "REQUEST_REJECTED";
    return "PROVIDER_UNAVAILABLE";
  }

  static BusinessException ai(int http, String code) {
    return switch (aiStatus(http, code)) {
      case "QUOTA_EXHAUSTED" -> new BusinessException(503, "AI 免费额度已用尽、失效或账户额度不足；请核对供应商控制台与免费保护，不会自动转付费");
      case "KEY_INVALID" -> new BusinessException(503, "AI 服务鉴权失败，请核对服务端 Key 与地域配置");
      case "MODEL_UNAVAILABLE" -> new BusinessException(503, "AI 模型、权限或接口路径不可用，请核对模型ID、地域及兼容接口地址");
      case "RATE_LIMITED" -> new BusinessException(429, "AI 请求达到供应商限流，请稍后手动重试");
      case "TIMEOUT" -> new BusinessException(504, "AI 提供方响应超时，请稍后手动重试");
      case "REQUEST_REJECTED" -> new BusinessException(502, "AI 提供方拒绝请求参数，请核对模型对JSON输出、非思考模式和输出上限的支持；未自动重试");
      default -> new BusinessException(502, "AI 提供方暂不可用，未保存任何调整，请稍后手动重试");
    };
  }

  static BusinessException geo(int http, String code) {
    if (Set.of("10003", "10010", "10019", "10020", "10021", "10044", "10045").contains(code))
      return new BusinessException(503, "高德调用配额已达到上限，当前查询已停止，请核对控制台");
    if (http == 429 || Set.of("10004", "10014", "10015", "10016", "10029").contains(code))
      return new BusinessException(429, "高德请求达到限流，请稍后手动重试");
    if (http == 401
        || Set.of("10001", "10005", "10006", "10007", "10009", "10012", "10013").contains(code))
      return new BusinessException(503, "高德服务鉴权失败，请核对 Key 类型与来源限制");
    if (http == 403 || "10002".equals(code) || "10026".equals(code))
      return new BusinessException(503, "高德对应接口权限不可用，请核对控制台");
    return new BusinessException(502, "高德服务暂不可用，请稍后手动重试");
  }
}
