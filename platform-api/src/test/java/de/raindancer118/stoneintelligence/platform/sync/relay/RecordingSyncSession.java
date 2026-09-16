package de.raindancer118.stoneintelligence.platform.sync.relay;

import java.util.ArrayList;
import java.util.List;

final class RecordingSyncSession implements SyncSession {

    private final String id;
    final List<byte[]> receivedDocUpdates = new ArrayList<>();
    final List<byte[]> receivedAwarenessUpdates = new ArrayList<>();
    Integer closedWithCode;
    String closedWithReason;

    RecordingSyncSession(String id) {
        this.id = id;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public void sendDocUpdate(byte[] payload) {
        receivedDocUpdates.add(payload);
    }

    @Override
    public void sendAwarenessUpdate(byte[] payload) {
        receivedAwarenessUpdates.add(payload);
    }

    @Override
    public void close(int code, String reason) {
        this.closedWithCode = code;
        this.closedWithReason = reason;
    }
}
