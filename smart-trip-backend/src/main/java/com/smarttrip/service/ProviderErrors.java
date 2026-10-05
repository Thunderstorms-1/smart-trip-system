package com.smarttrip.service;

import com.smarttrip.common.BusinessException;
import java.util.Set;

/** Upstream credentials and provider messages must never be forwarded to users. */
final class ProviderErrors {
  private ProviderErrors() {}

  static BusinessException ai(int http, String code) {
    if ("AllocationQuota.FreeTierOnly".equals(code) || "QuotaExhausted".equals(code))
      return new BusinessException(503, "AI 免费额度已用尽或失效，服务已停止；请核对供应商控制台");
    if (http == 401) return new BusinessException(503, "AI 服务鉴权失败，请核对服务端 Key 与地域配置");
    if (http == 429) return new BusinessException(429, "AI 请求达到供应商限流，请稍后手动重试");
    if (http == 403) return new BusinessException(503, "AI 模型权限或免费额度保护拒绝了请求，请核对控制台");
    return new BusinessException(502, "AI 提供方暂不可用，未保存任何调整，请稍后手动重试");
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
