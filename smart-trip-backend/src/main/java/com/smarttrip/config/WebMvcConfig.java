package com.smarttrip.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

  @Autowired private JwtAuthInterceptor jwtAuthInterceptor;
  @Autowired private AuthRateLimitInterceptor authRateLimitInterceptor;

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(authRateLimitInterceptor).addPathPatterns("/api/auth/**").order(-10);
    registry
        .addInterceptor(jwtAuthInterceptor)
        .addPathPatterns("/api/**")
        .excludePathPatterns("/api/auth/**", "/api/user/avatars/*");
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    // 配置全局跨域，方便前端 Vue 联调
    registry
        .addMapping("/**")
        .allowedOriginPatterns("http://localhost:[*]", "http://127.0.0.1:[*]")
        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH")
        .allowedHeaders("*")
        .allowCredentials(false)
        .maxAge(3600);
  }
}
