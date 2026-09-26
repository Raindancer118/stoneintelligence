package de.tstieh.stoneintelligence.platform.ai;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import de.tstieh.stoneintelligence.domain.id.VaultId;

public interface AiChangeSetRepository {

    AiChangeSet create(VaultId vaultId, String service, String agent, String requestedBy, String label, Instant at);

    /** Vault-gescopt: ein Change-Set eines anderen Vaults ist nicht auffindbar. */
    Optional<AiChangeSet> find(VaultId vaultId, UUID id);

    List<AiChangeSet> list(VaultId vaultId, int limit);

    void addChange(AiChange change);

    /** In Reihenfolge des Entstehens. */
    List<AiChange> changes(UUID changeSetId);

    /** Atomar: nur ein Aufrufer kann ein Change-Set als rueckgaengig gemacht markieren. */
    boolean markReverted(UUID id, Instant at);

    /** Loescht Change-Sets (samt Textkopien), die vor {@code cutoff} angelegt wurden; liefert die Anzahl. */
    int purgeCreatedBefore(Instant cutoff);

    /** Ziele, auf die {@code source} schon einmal verlinkt wurde (ADR 0012) - die werden nie erneut gesetzt. */
    java.util.Set<de.tstieh.stoneintelligence.domain.id.NoteId> linkedTargets(de.tstieh.stoneintelligence.domain.id.VaultId vaultId,
                                                                             de.tstieh.stoneintelligence.domain.id.NoteId source);

    void rememberLink(de.tstieh.stoneintelligence.domain.id.VaultId vaultId, de.tstieh.stoneintelligence.domain.id.NoteId source,
                      de.tstieh.stoneintelligence.domain.id.NoteId target, java.time.Instant at);

    /** Abgelehnte Ziele einer Quelle, je mit den Text-Hashes beider Notizen zur Zeit der Ablehnung. */
    java.util.Map<de.tstieh.stoneintelligence.domain.id.NoteId, java.util.List<String>> rejectedTargets(
        de.tstieh.stoneintelligence.domain.id.VaultId vaultId, de.tstieh.stoneintelligence.domain.id.NoteId source);

    void rememberRejection(de.tstieh.stoneintelligence.domain.id.VaultId vaultId, de.tstieh.stoneintelligence.domain.id.NoteId source,
                           de.tstieh.stoneintelligence.domain.id.NoteId target, String sourceHash, String targetHash, java.time.Instant at);

    void rememberRelation(de.tstieh.stoneintelligence.domain.id.VaultId vaultId, de.tstieh.stoneintelligence.domain.id.NoteId source,
                          de.tstieh.stoneintelligence.domain.id.NoteId target, LinkRelation relation, UUID changeSetId, java.time.Instant at);

    /** Beziehungen, die von {@code source} ausgehen. */
    java.util.Map<de.tstieh.stoneintelligence.domain.id.NoteId, LinkRelation> relationsFrom(
        de.tstieh.stoneintelligence.domain.id.VaultId vaultId, de.tstieh.stoneintelligence.domain.id.NoteId source);
}
