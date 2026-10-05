package com.sebratel.dashboards.common.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * {@code /interno/**} (e.g. the support team sheet sent by n8n) only answers on a second port,
 * {@code app.interno.porta}, which the stack does NOT publish: only containers on the same Docker
 * networks (n8n) reach it, so it needs no token. On the public port these paths return 404.
 * 0 (default) = no second port, {@code /interno/**} always 404.
 */
@Configuration
public class PortaInterna implements WebMvcConfigurer, WebServerFactoryCustomizer<TomcatServletWebServerFactory> {

    private final int porta;

    public PortaInterna(@Value("${app.interno.porta:0}") int porta) {
        this.porta = porta;
    }

    @Override
    public void customize(TomcatServletWebServerFactory factory) {
        if (porta > 0) {
            Connector c = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
            c.setPort(porta);
            factory.addAdditionalTomcatConnectors(c);
        }
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(filtro()).addPathPatterns("/interno/**");
    }

    HandlerInterceptor filtro() {
        return new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) {
                if (porta > 0 && req.getLocalPort() == porta) {
                    return true;
                }
                resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
                return false;
            }
        };
    }
}
