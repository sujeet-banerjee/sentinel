package com.sentinel.api.config;

import com.sentinel.api.service.ArchitecturalReviewHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;

import java.util.Map;

@Configuration
public class WebSocketConfig {

    @Bean
    public HandlerMapping webSocketHandlerMapping(ArchitecturalReviewHandler handler) {
        // This maps the URL to the specific handler logic
        return new SimpleUrlHandlerMapping(Map.of("/ws/analyze", handler), -1);
    }

    @Bean
    public WebSocketHandlerAdapter handlerAdapter() {
        // This allows WebFlux to actually run the WebSocket logic
        return new WebSocketHandlerAdapter();
    }
}