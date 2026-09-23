package de.raindancer118.stoneintelligence.platform.ai;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;
import de.raindancer118.stoneintelligence.domain.id.VaultId;
import de.raindancer118.stoneintelligence.platform.identity.Permission;
import de.raindancer118.stoneintelligence.platform.vault.VaultAccessGuard;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Was die KI in einem Vault getan hat - ansehen und rueckgaengig machen (ADR 0008). */
@RestController
public class AiController {

    private static final int LIST_LIMIT = 100;

    private final AiWriteService ai;
    private final AiServiceDirectory services;
    private final VaultAccessGuard access;

    public AiController(@Lazy AiWriteService ai, AiServiceDirectory services, VaultAccessGuard access) {
        this.ai = ai;
        this.services = services;
        this.access = access;
    }

    /** Welche KI-Dienste angebunden sind und welche Levels sie verarbeiten duerfen. */
    @GetMapping("/api/v1/ai/services")
    public List<AiServiceResponse> services() {
        return services.all().stream().map(AiServiceResponse::from).toList();
    }

    @GetMapping("/api/v1/vaults/{vaultId}/ai/change-sets")
    public List<ChangeSetResponse> list(@PathVariable String vaultId, Authentication auth) {
        var vId = VaultId.of(vaultId);
        access.require(vId, auth.getName(), Permission.READ);
        return ai.changeSets(vId, LIST_LIMIT).stream().map(ChangeSetResponse::from).toList();
    }

    @GetMapping("/api/v1/vaults/{vaultId}/ai/change-sets/{changeSetId}")
    public ChangeSetDetail detail(@PathVariable String vaultId, @PathVariable UUID changeSetId, Authentication auth) {
        var vId = VaultId.of(vaultId);
        access.require(vId, auth.getName(), Permission.READ);
        var changeSet = ai.changeSet(vId, changeSetId).orElseThrow(() -> new AiWriteRefusedException("KI-Änderung nicht gefunden"));
        var changes = ai.changes(vId, changeSetId);
        var readable = access.readablePaths(vId, auth.getName(), changes.stream().map(AiChange::path).toList(), UnaryOperator.identity());
        return new ChangeSetDetail(ChangeSetResponse.from(changeSet), changes.stream()
            .filter(change -> readable.contains(change.path()))
            .map(change -> new ChangeResponse(change.noteId().value().toString(), change.path(), change.kind().name(), change.at()))
            .toList());
    }

    @PostMapping("/api/v1/vaults/{vaultId}/ai/change-sets/{changeSetId}/revert")
    public AiRevertReport revert(@PathVariable String vaultId, @PathVariable UUID changeSetId, Authentication auth) {
        var vId = VaultId.of(vaultId);
        access.require(vId, auth.getName(), Permission.WRITE);
        access.require(vId, auth.getName(), Permission.DELETE);
        var report = ai.revert(vId, changeSetId, auth.getName());
        var readable = access.readablePaths(vId, auth.getName(),
            report.conflicts().stream().map(AiRevertConflict::path).toList(), UnaryOperator.identity());
        return new AiRevertReport(report.reverted(),
            report.conflicts().stream().filter(conflict -> readable.contains(conflict.path())).toList());
    }

    public record ChangeSetResponse(UUID id, String service, String agent, String requestedBy, String label,
                                    Instant createdAt, Instant revertedAt) {
        static ChangeSetResponse from(AiChangeSet set) {
            return new ChangeSetResponse(set.id(), set.service(), set.agent(), set.requestedBy(), set.label(), set.createdAt(),
                set.revertedAt());
        }
    }

    public record ChangeResponse(String noteId, String path, String kind, Instant at) { }
    public record ChangeSetDetail(ChangeSetResponse changeSet, List<ChangeResponse> changes) { }
}
