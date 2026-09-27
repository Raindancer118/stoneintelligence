package de.tstieh.stoneintelligence.platform.security;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityConfigTest {

    @Test
    void should_allowConfiguredWebappOrigin_forCrossOriginRequests() {
        var source = new SecurityConfig("https://kb.tstieh.de").corsConfigurationSource();
        HttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/vaults");

        var config = source.getCorsConfiguration(request);

        assertThat(config).isNotNull();
        assertThat(config.getAllowedOrigins()).contains("https://kb.tstieh.de");
        assertThat(config.getAllowedMethods()).contains("GET", "POST", "PATCH", "DELETE");
        assertThat(config.getAllowedHeaders()).contains("Authorization", "Content-Type");
    }

    // Selbst gehostet: dieselbe Variable wie fuer die Einladungslinks, Pfad und Schraegstrich egal.
    @Test
    void should_allowTheWebappOfASelfHostedInstance() {
        var source = new SecurityConfig("https://notes.example.org/app/").corsConfigurationSource();

        var config = source.getCorsConfiguration(new MockHttpServletRequest("GET", "/api/v1/vaults"));

        assertThat(config).isNotNull();
        assertThat(config.checkOrigin("https://notes.example.org")).isNotNull();
        assertThat(config.checkOrigin("https://kb.tstieh.de")).isNull();
        assertThat(config.checkOrigin("http://localhost:5173")).isNotNull();
    }

    @Test
    void should_notAllowArbitraryOrigin() {
        var source = new SecurityConfig("https://kb.tstieh.de").corsConfigurationSource();
        HttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/vaults");

        var config = source.getCorsConfiguration(request);

        assertThat(config).isNotNull();
        assertThat(config.checkOrigin("https://evil.example")).isNull();
    }

    @Test
    void should_allowAnyOrigin_forTheWebSocketHandshake() {
        // Regression: Spring Securitys CorsFilter lehnt eine ECHTE (nicht nur Preflight-)Anfrage
        // mit einem nicht erlaubten Origin pauschal mit 403 ab - unabhaengig von
        // `.permitAll()` in der Autorisierung, das ist eine fruehere, davon getrennte Pruefung.
        // Obsidian Desktop (Electron) schickt einen Origin, der nie in allowedOrigins
        // (nur die Webapp) stehen kann - der WS-Handshake braucht diesen Schutz auch gar nicht,
        // er ist ueber das Single-Use-Ticket selbst abgesichert (s. TicketHandshakeInterceptor).
        // Live beobachtet: jeder Verbindungsversuch von Obsidian Desktop scheiterte mit 403,
        // bevor das Ticket ueberhaupt geprueft wurde.
        var source = new SecurityConfig("https://kb.tstieh.de").corsConfigurationSource();
        HttpServletRequest request = new MockHttpServletRequest("GET", "/ws/sync");

        var config = source.getCorsConfiguration(request);

        assertThat(config).isNotNull();
        assertThat(config.checkOrigin("app://obsidian.md")).isNotNull();
        assertThat(config.checkOrigin("https://anything.example")).isNotNull();
    }
}
