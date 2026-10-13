package com.smarttrip.common;

public class AuthRateLimitException extends BusinessException {
  private final long retryAfter;

  public AuthRateLimitException(long retryAfter) {
    super(429, "操作过于频繁，请在" + retryAfter + "秒后重试");
    this.retryAfter = retryAfter;
  }

  public long getRetryAfter() {
    return retryAfter;
  }
}
