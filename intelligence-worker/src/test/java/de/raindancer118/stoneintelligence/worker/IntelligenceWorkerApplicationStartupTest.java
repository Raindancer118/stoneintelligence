package de.raindancer118.stoneintelligence.worker;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reiner Wiring-Smoke-Test ohne Datenbank, analog PlatformApiApplicationStartupTest.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "spring.autoconfigure.exclude="
        + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
        + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
        + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
)
class IntelligenceWorkerApplicationStartupTest {

    @Test
    void should_loadApplicationContext_when_startingWithoutDatabase(ApplicationContext context) {
        assertThat(context).isNotNull();
        assertThat(context.getBeansWithAnnotation(org.springframework.boot.autoconfigure.SpringBootApplication.class))
            .containsKey("intelligenceWorkerApplication");
    }
}
