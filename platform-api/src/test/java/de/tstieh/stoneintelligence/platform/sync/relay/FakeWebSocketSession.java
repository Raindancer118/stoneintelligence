package de.tstieh.stoneintelligence.platform.sync.relay;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * Minimales Test-Double statt Mockito (Projektstil: Fakes ueber Ports/Infrastruktur-Grenzen
 * statt Mocking-Framework). Nur die von {@link SyncWebSocketHandler} tatsaechlich genutzten
 * Methoden sind funktional - der Rest wirft {@link UnsupportedOperationException}.
 */
final class FakeWebSocketSession implements WebSocketSession {

    private final String id = UUID.randomUUID().toString();
    private final Map<String, Object> attributes = new HashMap<>();
    final List<WebSocketMessage<?>> sentMessages = new ArrayList<>();
    private boolean open = true;

    @Override
    public String getId() {
        return id;
    }

    @Override
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    @Override
    public void sendMessage(WebSocketMessage<?> message) {
        sentMessages.add(message);
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public void close() {
        open = false;
    }

    @Override
    public void close(CloseStatus status) {
        open = false;
    }

    @Override
    public URI getUri() {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpHeaders getHandshakeHeaders() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Principal getPrincipal() {
        throw new UnsupportedOperationException();
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        throw new UnsupportedOperationException();
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getAcceptedProtocol() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setTextMessageSizeLimit(int messageSizeLimit) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getTextMessageSizeLimit() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setBinaryMessageSizeLimit(int messageSizeLimit) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getBinaryMessageSizeLimit() {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<WebSocketExtension> getExtensions() {
        throw new UnsupportedOperationException();
    }
}
