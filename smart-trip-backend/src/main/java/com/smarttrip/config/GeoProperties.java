package com.smarttrip.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "geo")
public class GeoProperties {
  private boolean enabled;
  private boolean poiEnabled;
  private boolean routeEnabled;
  private boolean weatherEnabled;
  private boolean mapEnabled;
  private boolean freeQuotaVerified;
  private String amapKey = "";
  private String amapJsKey = "";
  private String amapSecurityKey = "";
}
