package de.tstieh.stoneintelligence.platform.audit;

import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.platform.identity.Permission;
import de.tstieh.stoneintelligence.platform.vault.VaultAccessGuard;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Aufbewahrungsfrist des Protokolls (ADR 0015) - nur mit Verwalten-Recht. */
@RestController
public class AuditRetentionController {

    private final VaultAccessGuard access;
    private final AuditRetentionService retention;

    public AuditRetentionController(VaultAccessGuard access, AuditRetentionService retention) {
        this.access = access;
        this.retention = retention;
    }

    @GetMapping("/api/v1/vaults/{vaultId}/audit/retention")
    public RetentionResponse get(@PathVariable String vaultId, Authentication auth) {
        var vId = VaultId.of(vaultId);
        access.require(vId, auth.getName(), Permission.MANAGE);
        return RetentionResponse.of(retention.retentionDays(vId));
    }

    @PutMapping("/api/v1/vaults/{vaultId}/audit/retention")
    public RetentionResponse put(@PathVariable String vaultId, @RequestBody ChangeRequest request, Authentication auth) {
        var vId = VaultId.of(vaultId);
        access.require(vId, auth.getName(), Permission.MANAGE);
        retention.change(vId, auth.getName(), request.days());
        return RetentionResponse.of(retention.retentionDays(vId));
    }

    /** {@code days == null}: unbegrenzt aufbewahren. */
    public record ChangeRequest(Integer days) {
    }

    public record RetentionResponse(Integer days, int minimumDays) {
        static RetentionResponse of(Integer days) {
            return new RetentionResponse(days, AuditRetention.MINIMUM_DAYS);
        }
    }
}
