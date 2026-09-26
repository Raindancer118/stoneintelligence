package de.tstieh.stoneintelligence.platform.files;

public class FileQuotaExceededException extends RuntimeException {

    public FileQuotaExceededException(long quotaBytes) {
        super("Der Speicherplatz dieses Vaults (" + quotaBytes / (1024 * 1024) + " MB) ist ausgeschöpft");
    }
}
