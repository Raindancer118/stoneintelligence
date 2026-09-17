package de.raindancer118.stoneintelligence.platform.security;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityConfigTest {

    @Test
    void should_allowConfiguredWebappOrigin_forCrossOriginRequests() {
        var source = new SecurityConfig().corsConfigurationSource();
        HttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/vaults");

        var config = source.getCorsConfiguration(request);

        assertThat(config).isNotNull();
        assertThat(config.getAllowedOrigins()).contains("https://kb.tstieh.de");
        assertThat(config.getAllowedMethods()).contains("GET", "POST", "PATCH", "DELETE");
        assertThat(config.getAllowedHeaders()).contains("Authorization", "Content-Type");
    }

    @Test
    void should_notAllowArbitraryOrigin() {
        var source = new SecurityConfig().corsConfigurationSource();
        HttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/vaults");

        var config = source.getCorsConfiguration(request);

        assertThat(config).isNotNull();
        assertThat(config.checkOrigin("https://evil.example")).isNull();
    }
}
