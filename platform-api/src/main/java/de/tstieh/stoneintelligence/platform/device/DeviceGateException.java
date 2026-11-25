package de.tstieh.stoneintelligence.platform.device;

/** Ein Geraet darf nicht synchronisieren; {@code code} ist fuer das Plugin maschinenlesbar. */
public final class DeviceGateException extends RuntimeException {

    private final String code;

    public DeviceGateException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
