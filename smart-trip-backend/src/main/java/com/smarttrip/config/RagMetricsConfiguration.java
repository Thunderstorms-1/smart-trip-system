package com.smarttrip.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** In-memory metrics by default; a deployment can supply its normal exporting registry. */
@Configuration
public class RagMetricsConfiguration {
  @Bean
  @ConditionalOnMissingBean(MeterRegistry.class)
  MeterRegistry ragMeterRegistry() {
    return new SimpleMeterRegistry();
  }
}
