package de.tstieh.stoneintelligence.platform.device;

import java.util.List;

/** Bewertung eines Geraets gegen die Richtlinie. {@code offending} nennt die verletzenden Plugins. */
public record DeviceState(Status status, List<PluginRef> offending) {

    public enum Status {
        COMPLIANT,
        NON_COMPLIANT,
        REVOKED,
        WIPE_PENDING,
        UNKNOWN
    }

    public boolean compliant() {
        return status == Status.COMPLIANT;
    }

    /** Sperre und Loeschauftrag gelten auch ohne Durchsetzung - sie sind eine ausdrueckliche Entscheidung. */
    public static DeviceState of(PluginPolicy policy, Device device) {
        if (device == null) {
            return new DeviceState(policy.enforcing() ? Status.UNKNOWN : Status.COMPLIANT, List.of());
        }
        if (device.wipePending()) {
            return new DeviceState(Status.WIPE_PENDING, List.of());
        }
        if (device.revoked()) {
            return new DeviceState(Status.REVOKED, List.of());
        }
        var offending = policy.violations(device.plugins());
        return offending.isEmpty()
            ? new DeviceState(Status.COMPLIANT, List.of())
            : new DeviceState(Status.NON_COMPLIANT, offending);
    }
}
