package de.tstieh.stoneintelligence.platform.sync.relay;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    /**
     * Der eingebettete Tomcat begrenzt WebSocket-Binaernachrichten standardmaessig auf 8 KiB
     * (JSR-356-Default) - jede Notiz mit mehr Inhalt (schnell erreicht, z. B. laengere Markdown-
     * Dokumente oder eingebettete Base64-Anhaenge) hat die Verbindung dieses Clients hart
     * getrennt, OHNE Fehlermeldung im Relay (live per Simulation gefunden:
     * {@code VaultSyncSimulationIT}, eine 300-KB-Notiz brach die Verbindung sofort ab). 16 MiB
     * deckt realistische Notizgroessen mit Puffer ab, ohne beliebig grosse Payloads zuzulassen.
     */
    private static final int MAX_MESSAGE_BUFFER_BYTES = 16 * 1024 * 1024;

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

    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        var container = new ServletServerContainerFactoryBean();
        container.setMaxBinaryMessageBufferSize(MAX_MESSAGE_BUFFER_BYTES);
        container.setMaxTextMessageBufferSize(MAX_MESSAGE_BUFFER_BYTES);
        return container;
    }
}
