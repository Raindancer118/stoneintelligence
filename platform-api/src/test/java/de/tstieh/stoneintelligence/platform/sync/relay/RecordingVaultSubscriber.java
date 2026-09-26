package de.tstieh.stoneintelligence.platform.sync.relay;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import de.tstieh.stoneintelligence.domain.id.NoteId;

/** Test-Doppel fuer einen Vault-Abonnenten - merkt sich, was ihm zugestellt wurde. */
class RecordingVaultSubscriber implements VaultSubscriber {

    record Received(byte messageType, NoteId noteId, String path) { }

    private final String id;
    private final Set<String> readablePaths;
    private final boolean failOnSend;
    final List<Received> received = new ArrayList<>();
    final Set<NoteId> joined = new java.util.HashSet<>();
    boolean contentUpdates = true;
    boolean folderEvents = true;

    RecordingVaultSubscriber(String id) {
        this(id, null, false);
    }

    /** {@code readablePaths == null} heisst "darf alles lesen". */
    RecordingVaultSubscriber(String id, Set<String> readablePaths, boolean failOnSend) {
        this.id = id;
        this.readablePaths = readablePaths;
        this.failOnSend = failOnSend;
    }

    static RecordingVaultSubscriber failing(String id) {
        return new RecordingVaultSubscriber(id, null, true);
    }

    static RecordingVaultSubscriber readingOnly(String id, String... paths) {
        return new RecordingVaultSubscriber(id, Set.of(paths), false);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public boolean wantsContentUpdates() {
        return contentUpdates;
    }

    @Override
    public boolean wantsFolderEvents() {
        return folderEvents;
    }

    @Override
    public boolean hasJoined(NoteId noteId) {
        return joined.contains(noteId);
    }

    @Override
    public boolean mayRead(String path) {
        return readablePaths == null || readablePaths.contains(path);
    }

    @Override
    public void sendVaultEvent(byte messageType, NoteId noteId, String path) {
        if (failOnSend) {
            throw new SyncSessionSendException("tote Verbindung", new IllegalStateException("test"));
        }
        received.add(new Received(messageType, noteId, path));
    }
}
