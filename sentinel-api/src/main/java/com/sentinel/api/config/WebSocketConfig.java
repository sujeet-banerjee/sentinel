package com.sentinel.api.config;

import com.sentinel.api.service.ArchitecturalReviewHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;

import java.util.Map;

/**
 * The reactive infrastructure configuration layer responsible for WebSocket protocol 
 * upgrades and request routing.
 * * <p><b>Main Purpose:</b></p>
 * This class enforces a strict separation of concerns between networking infrastructure 
 * and business logic. It registers the URL endpoints (e.g., {@code /ws/analyze}) and 
 * maps them to their respective {@link org.springframework.web.reactive.socket.WebSocketHandler} 
 * implementations, ensuring the handlers themselves remain purely focused on stream orchestration.
 * * <p><b>Encapsulated Details:</b></p>
 * <ul>
 * <li><b>HandlerMapping:</b> Utilizes {@link org.springframework.web.reactive.handler.SimpleUrlHandlerMapping} 
 * to define the URI-to-Handler dictionary. It is configured with an order of {@code -1} to ensure 
 * WebSocket upgrade requests take precedence over standard HTTP REST mappings.</li>
 * <li><b>Adapter:</b> Provisions a {@link org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter} 
 * bean, which acts as the bridge allowing Spring WebFlux's DispatcherHandler to natively 
 * execute the mapped WebSocket handlers.</li>
 * </ul>
 */
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