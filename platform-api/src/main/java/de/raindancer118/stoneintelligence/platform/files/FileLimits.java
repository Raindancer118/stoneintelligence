package de.raindancer118.stoneintelligence.platform.files;

/** Grenzen je Datei und je Vault in Bytes (ADR 0009 Punkt 6). */
public record FileLimits(long maxFileBytes, long vaultQuotaBytes) {

    public static FileLimits ofMegabytes(long maxFileMb, long vaultQuotaMb) {
        return new FileLimits(maxFileMb * 1024 * 1024, vaultQuotaMb * 1024 * 1024);
    }
}
