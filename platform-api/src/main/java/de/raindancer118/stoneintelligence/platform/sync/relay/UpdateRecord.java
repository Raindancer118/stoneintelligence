package de.raindancer118.stoneintelligence.platform.sync.relay;

/** Ein persistiertes, rohes Yjs-Update (der Server interpretiert den Inhalt nicht - "dummer Server"). */
public record UpdateRecord(long serverSequence, byte[] payload, boolean ciphertext) {
}
