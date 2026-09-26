package de.tstieh.stoneintelligence.platform.files;

/** Gespeicherte Bytes: ihr SHA-256 (hex) ist zugleich ihre Adresse. */
public record StoredBlob(String sha256, long size) {
}
