package de.tstieh.stoneintelligence.mcpadapter;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class McpAdapterApplicationStartupTest {

    @Test
    void should_loadApplicationContext_when_starting(ApplicationContext context) {
        assertThat(context).isNotNull();
        assertThat(context.getBeansWithAnnotation(org.springframework.boot.autoconfigure.SpringBootApplication.class))
            .containsKey("mcpAdapterApplication");
    }
}
