package de.tstieh.stoneintelligence.platform.audit;

import java.time.Instant;
import java.util.Map;
import de.tstieh.stoneintelligence.domain.id.VaultId;

/** Frist je Vault und das Loeschen abgelaufener Ereignisse - als Port, damit der Dienst ohne Datenbank testbar ist. */
public interface AuditRetentionRepository {

    /** {@code null}: unbegrenzt. */
    Integer retentionDays(VaultId vaultId);

    void setRetentionDays(VaultId vaultId, Integer days);

    /** Nur Vaults mit Frist. */
    Map<VaultId, Integer> allRetentions();

    /** Loescht Ereignisse vor {@code before}, ausser denen nach {@link AuditRetention#isKept}; liefert die Anzahl. */
    int deleteExpired(VaultId vaultId, Instant before);
}
