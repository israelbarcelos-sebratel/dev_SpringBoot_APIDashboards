package com.sebratel.dashboards.common.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")      // Modificado de "/api/**" para "/**" (libera todas as rotas)
                .allowedOrigins("*")    // Modificado para "*" (libera todas as origens/sites)
                .allowedMethods("*")    // Modificado de "GET" para "*" (libera todos os métodos: POST, PUT, DELETE, etc.)
                .allowedHeaders("*");   // Mantido "*" (libera todos os cabeçalhos)
    }
}