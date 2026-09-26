package de.tstieh.stoneintelligence.platform.ai;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import de.tstieh.stoneintelligence.domain.id.VaultId;

public interface LinkingSettingsRepository {

    Optional<LinkingSettings> find(VaultId vaultId);

    void save(LinkingSettings settings);

    /** Vaults, in denen die naechtliche Verlinkung eingeschaltet ist. */
    List<LinkingSettings> enabled();

    void markRun(VaultId vaultId, Instant at);

    /** Wer eingewilligt hat, dass Auszuege seiner Notizen im Modus AI an den KI-Anbieter gehen. */
    java.util.Set<String> aiConsents(VaultId vaultId);

    void setAiConsent(VaultId vaultId, String subject, boolean consent, Instant at);
}
