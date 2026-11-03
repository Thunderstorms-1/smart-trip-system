package com.smarttrip.rag.knowledge;

/** Stable diagnostic code and sanitized message; never carries supplier bodies or credentials. */
public class RagException extends RuntimeException {
  private final String code;
  public RagException(String code, String safeMessage) {
    super(safeMessage);
    this.code = code;
  }
  public String code() { return code; }
}