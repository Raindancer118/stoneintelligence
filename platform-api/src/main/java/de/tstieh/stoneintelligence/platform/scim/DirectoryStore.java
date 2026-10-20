package de.tstieh.stoneintelligence.platform.scim;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Was der IdP per SCIM ueber Konten und Gruppen meldet (ADR 0013). Wie daraus Mitgliedschaften in
 * Vaults werden, entscheidet die Sicht {@code effective_group_members} - hier wird nur gespeichert.
 */
public interface DirectoryStore {

    /** {@code subject}: der Wert des Principal-Claims dieses Kontos (userName oder externalId, s. {@link ScimSettings}). */
    record AccountData(String subject, String userName, String externalId, String displayName, boolean active) {
    }

    record Account(UUID id, String subject, String userName, String externalId, String displayName, boolean active,
                   Instant created, Instant lastModified) {
    }

    /** {@code idpKey}: unter diesem Namen verknuepfen Vaults die Gruppe. */
    record GroupData(String idpKey, String displayName, String externalId) {
    }

    record Group(UUID id, String idpKey, String displayName, String externalId, Instant created, Instant lastModified) {
    }

    record Member(UUID accountId, String displayName) {
    }

    /** {@code startIndex} 1-basiert wie in SCIM; {@code total} zaehlt alle Treffer, nicht nur diese Seite. */
    record Page<T>(List<T> items, int total) {
    }

    /**
     * Legt an ({@code id == null}) oder ersetzt. Ein geloeschtes Konto gleichen userNames wird beim Anlegen
     * wiederbelebt (gleiche ID). Belegter userName oder Subject: {@link ScimException#uniqueness}.
     */
    Account saveAccount(UUID id, AccountData data);

    Optional<Account> account(UUID id);

    /** {@code filter} darf {@code null} sein. Geloeschte Konten erscheinen nie. */
    Page<Account> accounts(ScimFilter filter, int startIndex, int count);

    /**
     * Sperrt das Konto, nimmt es aus allen Gruppen und vergisst Name und externalId; leer, wenn es nicht
     * (mehr) existiert. userName und Subject bleiben als Sperrvermerk bis {@link #purgeDeletedBefore}.
     */
    Optional<Account> deleteAccount(UUID id);

    /** Wie lange ein geloeschtes Konto gesperrt bleibt, bevor es ganz verschwindet (Datenminimierung). */
    java.time.Duration DELETED_RETENTION = java.time.Duration.ofDays(30);

    int purgeDeletedBefore(Instant before);

    Group saveGroup(UUID id, GroupData data);

    Optional<Group> group(UUID id);

    Page<Group> groups(ScimFilter filter, int startIndex, int count);

    List<Member> members(UUID groupId);

    /** Unbekannte Konten werden uebergangen: ein IdP meldet Gruppen oft vor allen ihren Konten. */
    void replaceMembers(UUID groupId, Set<UUID> accountIds);

    void addMembers(UUID groupId, Set<UUID> accountIds);

    void removeMembers(UUID groupId, Set<UUID> accountIds);

    boolean deleteGroup(UUID id);

    /** Die Gruppen-Schluessel eines Kontos - welche Vaults eine Aenderung an ihm trifft. */
    Set<String> idpKeysOf(UUID accountId);

    Set<String> blockedSubjects();
}
