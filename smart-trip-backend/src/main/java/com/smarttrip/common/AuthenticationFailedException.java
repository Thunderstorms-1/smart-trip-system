package com.smarttrip.common;

/** Distinguishes credential failures from input validation and infrastructure errors. */
public class AuthenticationFailedException extends BusinessException {
  public AuthenticationFailedException() {
    super(400, "账号或密码错误");
  }
}
