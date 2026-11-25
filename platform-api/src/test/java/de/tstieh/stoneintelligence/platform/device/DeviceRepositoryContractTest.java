package de.tstieh.stoneintelligence.platform.device;

import java.time.Instant;
import java.util.List;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Vertrag fuer {@code FakeDeviceRepository}, {@code FakePluginPolicyRepository} und die JDBC-Fassungen. */
abstract class DeviceRepositoryContractTest {

    static final Instant T0 = Instant.parse("2026-11-25T08:00:00Z");

    protected abstract DeviceRepository devices();

    protected abstract PluginPolicyRepository policies();

    protected abstract VaultId newVault();

    static DeviceReport report(String name, PluginRef... plugins) {
        return new DeviceReport(name, "linux", "1.15.0", "0.34.0", false, List.of(plugins), false);
    }

    @Test
    void should_defaultToOff_andRememberAPolicy() {
        var vault = newVault();
        assertThat(policies().find(vault)).isEqualTo(PluginPolicy.off(vault));

        policies().save(new PluginPolicy(vault, PolicyMode.ENFORCE,
            List.of(new PluginRef("calendar", "1.5.10"), new PluginRef("dataview", null))), "tom");

        var stored = policies().find(vault);
        assertThat(stored.mode()).isEqualTo(PolicyMode.ENFORCE);
        assertThat(stored.allowed()).containsExactly(new PluginRef("calendar", "1.5.10"), new PluginRef("dataview", null));

        policies().save(PluginPolicy.off(vault), "tom");
        assertThat(policies().find(vault).mode()).isEqualTo(PolicyMode.OFF);
    }

    @Test
    void should_storeAndReplaceADevice_includingRevokeAndWipeState() {
        var vault = newVault();
        var first = Device.first(vault, "dev-12345678", "tom", report("Laptop", new PluginRef("calendar", "1.5.10")), T0);
        devices().save(first);

        assertThat(devices().find(vault, "dev-12345678")).contains(first);

        var wiped = first.wipe("admin", T0.plusSeconds(60));
        devices().save(wiped);

        var read = devices().find(vault, "dev-12345678").orElseThrow();
        assertThat(read.wipePending()).isTrue();
        assertThat(read.revokedBy()).isEqualTo("admin");
        assertThat(read.plugins()).containsExactly(new PluginRef("calendar", "1.5.10"));
        assertThat(read.firstSeen()).isEqualTo(T0);
    }

    @Test
    void should_listNewestFirst_andOnlyOfTheVault() {
        var vault = newVault();
        var other = newVault();
        devices().save(Device.first(vault, "dev-aaaaaaaa", "tom", report("Alt"), T0));
        devices().save(Device.first(vault, "dev-bbbbbbbb", "ben", report("Neu"), T0.plusSeconds(600)));
        devices().save(Device.first(other, "dev-cccccccc", "tom", report("Fremd"), T0.plusSeconds(900)));

        assertThat(devices().list(vault)).extracting(Device::name).containsExactly("Neu", "Alt");
    }

    @Test
    void should_separateTheSameDeviceIdAcrossVaults() {
        var one = newVault();
        var two = newVault();
        devices().save(Device.first(one, "dev-12345678", "tom", report("Eins"), T0));

        assertThat(devices().find(two, "dev-12345678")).isEmpty();
    }
}
