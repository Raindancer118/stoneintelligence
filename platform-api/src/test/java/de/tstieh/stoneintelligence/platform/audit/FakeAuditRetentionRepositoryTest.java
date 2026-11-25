package de.tstieh.stoneintelligence.platform.audit;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import de.tstieh.stoneintelligence.domain.id.VaultId;

class FakeAuditRetentionRepositoryTest extends AuditRetentionRepositoryContractTest {

    private final FakeAuditRetentionRepository repository = new FakeAuditRetentionRepository();

    @Override
    protected AuditRetentionRepository repository() {
        return repository;
    }

    @Override
    protected VaultId newVault() {
        return VaultId.newId();
    }

    @Override
    protected void seed(VaultId vaultId, String actor, String action, Map<String, Object> payload, Instant at) {
        repository.seed(vaultId, actor, action, payload, at);
    }

    @Override
    protected List<String> remainingActions(VaultId vaultId) {
        return repository.remainingActions(vaultId);
    }
}
