package com.smarttrip.config;

import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Data
@Component
@Validated
@ConfigurationProperties(prefix = "observability")
public class ObservabilityProperties {
  private boolean requestTimingEnabled = true;

  @Min(1)
  private long slowRequestMillis = 500;
}
