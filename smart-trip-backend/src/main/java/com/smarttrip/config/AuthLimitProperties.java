package com.smarttrip.config;

import jakarta.validation.constraints.*;
import java.util.*;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Data
@Component
@Validated
@ConfigurationProperties(prefix = "security.auth-limits")
public class AuthLimitProperties {
  @Min(1)
  @Max(100000)
  private int loginIpLimit = 30;

  @Min(1)
  @Max(604800)
  private int loginIpWindowSeconds = 60;

  @Min(1)
  @Max(100000)
  private int registerIpLimit = 5;

  @Min(1)
  @Max(604800)
  private int registerIpWindowSeconds = 3600;

  @Min(1)
  @Max(100000)
  private int pairFailureLimit = 5;

  @Min(1)
  @Max(100000)
  private int accountFailureLimit = 20;

  @Min(1)
  @Max(604800)
  private int failureWindowSeconds = 900;

  @Min(1)
  @Max(3600)
  private int cooldownSeconds = 300;

  @Pattern(regexp = "[A-Za-z0-9:_-]{1,80}")
  private String keyPrefix = "smarttrip:auth:";

  private List<String> trustedProxies = new ArrayList<>();
}
