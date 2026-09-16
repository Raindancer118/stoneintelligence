package de.raindancer118.stoneintelligence.platform;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reiner Wiring-Smoke-Test ohne Datenbank: prüft, dass sich der Spring-Kontext aus unserer
 * eigenen Konfiguration fehlerfrei aufbaut. DB-/Flyway-/JPA-Autoconfiguration ist hier bewusst
 * ausgeschlossen - die Migration selbst wird in {@link PlatformSchemaMigrationIT} gegen einen
 * echten Postgres via Testcontainers geprüft (braucht einen laufenden Docker-Daemon).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "spring.autoconfigure.exclude="
        + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
        + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
        + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
)
class PlatformApiApplicationStartupTest {

    @Test
    void should_loadApplicationContext_when_startingWithoutDatabase(ApplicationContext context) {
        assertThat(context).isNotNull();
        assertThat(context.getBeansWithAnnotation(org.springframework.boot.autoconfigure.SpringBootApplication.class))
            .containsKey("platformApiApplication");
    }
}
