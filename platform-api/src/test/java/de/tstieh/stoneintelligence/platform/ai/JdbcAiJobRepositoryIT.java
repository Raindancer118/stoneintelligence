package de.tstieh.stoneintelligence.platform.ai;

import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.platform.vault.JdbcVaultRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Derselbe Vertrag wie {@link FakeAiJobRepositoryTest}, gegen echtes Postgres. */
@Testcontainers
class JdbcAiJobRepositoryIT extends AiJobRepositoryContractTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(de.tstieh.stoneintelligence.platform.TestImages.POSTGRES)
        .withDatabaseName("stoneintelligence").withUsername("stoneintelligence").withPassword("test");

    private static JdbcClient jdbcClient;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas("platform").locations("classpath:db/migration/platform").load().migrate();
        jdbcClient = JdbcClient.create(new SimpleDriverDataSource(
            new org.postgresql.Driver(), POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    @Override
    protected AiJobRepository repository() {
        jdbcClient.sql("TRUNCATE platform.ai_jobs").update();
        return new JdbcAiJobRepository(jdbcClient);
    }

    @Override
    protected VaultId existingVault() {
        return new JdbcVaultRepository(jdbcClient).create("KI-Test").id();
    }

    // Mehrere Worker gleichzeitig: jeder Job geht an genau einen (FOR UPDATE SKIP LOCKED).
    @org.junit.jupiter.api.Test
    void should_neverHandTheSameJobToTwoWorkers_atTheSameTime() throws Exception {
        var repository = repository();
        var vault = existingVault();
        var now = java.time.Instant.parse("2026-09-23T10:00:00Z");
        for (var i = 0; i < 20; i++) {
            repository.create(new NewAiJob(vault, "gemini", "tom", i + ".pdf", "application/pdf", 1, new byte[] {1}, 3), now.plusMillis(i));
        }
        var start = new java.util.concurrent.CyclicBarrier(8);
        var claimed = java.util.concurrent.ConcurrentHashMap.<java.util.UUID>newKeySet();
        var duplicates = new java.util.concurrent.atomic.AtomicInteger();
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (var worker = 0; worker < 8; worker++) {
                futures.add(executor.submit(() -> {
                    var own = new JdbcAiJobRepository(jdbcClient);
                    start.await();
                    for (var job = own.claim(now.plusSeconds(1), java.time.Duration.ofMinutes(5)); job.isPresent();
                         job = own.claim(now.plusSeconds(1), java.time.Duration.ofMinutes(5))) {
                        if (!claimed.add(job.get().id())) {
                            duplicates.incrementAndGet();
                        }
                    }
                    return null;
                }));
            }
            for (var future : futures) {
                future.get(30, java.util.concurrent.TimeUnit.SECONDS);
            }
        }

        org.assertj.core.api.Assertions.assertThat(claimed).hasSize(20);
        org.assertj.core.api.Assertions.assertThat(duplicates).hasValue(0);
    }
}
