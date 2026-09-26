package de.tstieh.stoneintelligence.platform.sync.relay;

public final class SyncSessionSendException extends RuntimeException {

    public SyncSessionSendException(String sessionId, Throwable cause) {
        super("failed to send update to sync session " + sessionId, cause);
    }
}
