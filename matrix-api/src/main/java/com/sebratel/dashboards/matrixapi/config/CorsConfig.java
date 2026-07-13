package com.sebratel.dashboards.matrixapi.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**") // Libera todos os endpoints da API
                .allowedOrigins("*") // Libera todas as origens (domínios)
                .allowedMethods("*") // Libera todos os métodos HTTP (GET, POST, PUT, etc.)
                .allowedHeaders("*"); // Libera todos os cabeçalhos de requisição
    }
}