package com.xinjian.capsulecode.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

@Configuration
public class CorsConfig {

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration allOriginsConfig = new CorsConfiguration();
        allOriginsConfig.addAllowedOrigin("*");
        allOriginsConfig.addAllowedMethod("*");
        allOriginsConfig.addAllowedHeader("*");
        allOriginsConfig.setAllowCredentials(false);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", allOriginsConfig);

        return new CorsFilter(source);
    }
}
