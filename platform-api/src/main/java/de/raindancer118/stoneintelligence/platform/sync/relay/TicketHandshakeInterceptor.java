package de.raindancer118.stoneintelligence.platform.sync.relay;

import java.util.Map;
import de.raindancer118.stoneintelligence.platform.sync.ticket.TicketService;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Validiert das Single-Use-Ticket VOR dem WebSocket-Handshake (Plan.md Abschnitt 3, Muster aus
 * stonesync). Der Obsidian-WS-Client kann keine Custom-Header senden - das Ticket kommt deshalb
 * als Query-Parameter {@code ticket}.
 */
@Component
public class TicketHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTR_VAULT_ID = "vaultId";
    public static final String ATTR_ACTOR = "actor";

    private final TicketService ticketService;

    public TicketHandshakeInterceptor(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    @Override
    public boolean beforeHandshake(
        ServerHttpRequest request, ServerHttpResponse response,
        WebSocketHandler wsHandler, Map<String, Object> attributes
    ) {
        var token = UriComponentsBuilder.fromUri(request.getURI())
            .build()
            .getQueryParams()
            .getFirst("ticket");

        if (token == null) {
            response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
            return false;
        }

        var claims = ticketService.redeem(token);
        if (claims.isEmpty()) {
            response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
            return false;
        }

        attributes.put(ATTR_VAULT_ID, claims.get().vaultId());
        attributes.put(ATTR_ACTOR, claims.get().actor());
        return true;
    }

    @Override
    public void afterHandshake(
        ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler, Exception exception
    ) {
        // nichts zu tun
    }
}
