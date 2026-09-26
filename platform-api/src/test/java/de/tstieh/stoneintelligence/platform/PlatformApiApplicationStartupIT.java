package de.tstieh.stoneintelligence.platform;

import de.tstieh.stoneintelligence.platform.security.TestJwtSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Voller Wiring-Smoke-Test gegen echtes Postgres (Testcontainers): seit dem vertikalen
 * Sync-Slice (Phase 2) braucht der Kontext eine echte DataSource (JdbcClient-basierte
 * Repositories) - ein Kontext-Load ohne DB waere kein ehrlicher Test mehr. Braucht einen
 * laufenden Docker-Daemon (s. Project.md). {@link TestJwtSupport} liefert seit Phase 3 den
 * einzigen {@code JwtDecoder} im Testkontext (die produktive, auf einen echten Authentik-Issuer
 * zeigende Bean existiert hier nicht, s. deren Javadoc).
 */
@Testcontainers
@Import(TestJwtSupport.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlatformApiApplicationStartupIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("stoneintelligence")
            .withUsername("stoneintelligence")
            .withPassword("test");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void should_loadApplicationContextAndMigrateSchema_when_startingAgainstRealPostgres(ApplicationContext context) {
        assertThat(context).isNotNull();
        assertThat(context.getBeansWithAnnotation(org.springframework.boot.autoconfigure.SpringBootApplication.class))
            .containsKey("platformApiApplication");
    }
}
