package de.raindancer118.stoneintelligence.platform.vault;

import de.raindancer118.stoneintelligence.domain.id.VaultId;
import de.raindancer118.stoneintelligence.platform.identity.AuthorizationRepository;
import de.raindancer118.stoneintelligence.platform.identity.Permission;
import de.raindancer118.stoneintelligence.platform.identity.PathRules;
import de.raindancer118.stoneintelligence.platform.identity.RuleEffect;
import org.springframework.stereotype.Component;

/**
 * Setzt die in ADR 0006 (Punkt 6) als "expliziter naechster Schritt, kein optionales Feature"
 * markierte scharfe Durchsetzung von {@code AuthorizationRepository.effectivePermissions} und
 * {@link PathRules} tatsaechlich als Vorbedingung um. {@code TopicRules} bleibt unverdrahtet -
 * {@link Note} traegt (noch) keine Themen-Zuordnung, dafuer fehlt das Datenmodell.
 */
@Component
public class VaultAccessGuard {

    private final AuthorizationRepository authorization;

    public VaultAccessGuard(AuthorizationRepository authorization) {
        this.authorization = authorization;
    }

    public void require(VaultId vaultId, String actor, Permission permission) {
        if (!authorization.effectivePermissions(vaultId, actor).contains(permission)) {
            throw new ForbiddenException(actor + " lacks " + permission + " in vault " + vaultId.value());
        }
    }

    public void requireReadablePaths(VaultId vaultId, String actor, java.util.List<String> paths) {
        var rules = authorization.listPathRules(vaultId);
        if (paths.stream().anyMatch(path -> PathRules.resolve(rules, path, actor) == RuleEffect.DENY)) {
            throw new ForbiddenException("audit contains a denied path");
        }
    }

    public java.util.List<Note> readableNotes(VaultId vaultId, String actor, java.util.List<Note> notes) {
        var rules = authorization.listPathRules(vaultId);
        return notes.stream().filter(note -> PathRules.resolve(rules, note.path(), actor) != RuleEffect.DENY).toList();
    }

    /** Nur die Pfade, die {@code actor} sehen darf; {@code asRulePath} bildet z. B. Ordner auf {@code <ordner>/} ab. */
    public java.util.List<String> readablePaths(VaultId vaultId, String actor, java.util.List<String> paths,
                                                java.util.function.UnaryOperator<String> asRulePath) {
        var rules = authorization.listPathRules(vaultId);
        return paths.stream().filter(path -> PathRules.resolve(rules, asRulePath.apply(path), actor) != RuleEffect.DENY).toList();
    }

    public void require(VaultId vaultId, String actor, Permission permission, String path) {
        require(vaultId, actor, permission);
        if (PathRules.resolve(authorization.listPathRules(vaultId), path, actor) == RuleEffect.DENY) {
            throw new ForbiddenException(actor + " is denied path '" + path + "' in vault " + vaultId.value());
        }
    }
}
