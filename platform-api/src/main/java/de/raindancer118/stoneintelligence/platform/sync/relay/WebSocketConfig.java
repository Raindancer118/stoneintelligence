package de.raindancer118.stoneintelligence.platform.sync.relay;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final SyncWebSocketHandler handler;
    private final TicketHandshakeInterceptor ticketHandshakeInterceptor;

    public WebSocketConfig(SyncWebSocketHandler handler, TicketHandshakeInterceptor ticketHandshakeInterceptor) {
        this.handler = handler;
        this.ticketHandshakeInterceptor = ticketHandshakeInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/sync")
            .addInterceptors(ticketHandshakeInterceptor)
            .setAllowedOriginPatterns("*");
    }
}
