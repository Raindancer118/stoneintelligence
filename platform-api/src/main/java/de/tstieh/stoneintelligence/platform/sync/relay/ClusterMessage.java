package de.tstieh.stoneintelligence.platform.sync.relay;

import java.util.Base64;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.platform.vault.NoteKind;

/**
 * Was eine platform-api-Instanz den anderen mitteilt (ADR 0013, Schritt 7). Nur Verweise und
 * kleine Nutzlasten: Postgres NOTIFY traegt hoechstens 8000 Bytes, ein Dokument-Update liest der
 * Empfaenger deshalb aus der Datenbank.
 */
public sealed interface ClusterMessage {

    /** Ein neues Update ist gespeichert; Empfaenger mit Sitzungen in diesem Raum lesen es nach. */
    record DocUpdated(NoteId noteId, long revision) implements ClusterMessage { }

    /** {@code sessionKey} ist ueber alle Instanzen eindeutig (Instanz/Session). */
    record Awareness(NoteId noteId, String sessionKey, byte[] payload) implements ClusterMessage { }

    record AwarenessGone(NoteId noteId, String sessionKey) implements ClusterMessage { }

    record NoteDeleted(NoteId noteId) implements ClusterMessage { }

    record VaultEvent(VaultId vaultId, byte messageType, NoteId noteId, NoteKind kind, String path) implements ClusterMessage { }

    record AccessChanged(VaultId vaultId) implements ClusterMessage { }

    record Envelope(String origin, ClusterMessage message) { }

    /** Feldtrenner, der in Pfaden und IDs nicht vorkommt. */
    char SEPARATOR = '\u001f';

    static String encode(String origin, ClusterMessage message) {
        var fields = switch (message) {
            case DocUpdated m -> new String[] {"U", m.noteId().value().toString(), Long.toString(m.revision())};
            case Awareness m -> new String[] {"A", m.noteId().value().toString(), m.sessionKey(),
                Base64.getEncoder().encodeToString(m.payload())};
            case AwarenessGone m -> new String[] {"G", m.noteId().value().toString(), m.sessionKey()};
            case NoteDeleted m -> new String[] {"D", m.noteId().value().toString()};
            case VaultEvent m -> new String[] {"V", m.vaultId().value().toString(), Byte.toString(m.messageType()),
                m.noteId().value().toString(), m.kind().name(), m.path()};
            case AccessChanged m -> new String[] {"X", m.vaultId().value().toString()};
        };
        var out = new StringBuilder(origin);
        for (var field : fields) {
            if (field.indexOf(SEPARATOR) >= 0) {
                throw new IllegalArgumentException("Feld enthaelt den Trenner");
            }
            out.append(SEPARATOR).append(field);
        }
        return out.toString();
    }

    static Envelope decode(String wire) {
        var f = wire.split(String.valueOf(SEPARATOR), -1);
        ClusterMessage message = switch (f[1]) {
            case "U" -> new DocUpdated(NoteId.of(f[2]), Long.parseLong(f[3]));
            case "A" -> new Awareness(NoteId.of(f[2]), f[3], Base64.getDecoder().decode(f[4]));
            case "G" -> new AwarenessGone(NoteId.of(f[2]), f[3]);
            case "D" -> new NoteDeleted(NoteId.of(f[2]));
            case "V" -> new VaultEvent(VaultId.of(f[2]), Byte.parseByte(f[3]), NoteId.of(f[4]), NoteKind.valueOf(f[5]), f[6]);
            case "X" -> new AccessChanged(VaultId.of(f[2]));
            default -> throw new IllegalArgumentException("Unbekannte Nachricht: " + f[1]);
        };
        return new Envelope(f[0], message);
    }
}
