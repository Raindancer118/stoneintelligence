package de.raindancer118.stoneintelligence.platform.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * PROVISORISCH fuer den vertikalen Sync-Slice (Plan.md Abschnitt 6, Phase 2): alle Endpunkte
 * offen, damit Ticket-Ausstellung, WebSocket-Handshake und Note-CRUD ueberhaupt erreichbar sind.
 * Ohne diese Konfiguration wuerde Spring Security wegen der vorhandenen
 * oauth2-client/resource-server-Starter automatisch ein Login/HTTP-Basic vor jeden Endpunkt
 * haengen.
 *
 * <p>Wird in Phase 3 (Identity &amp; Authorization, Plan.md Abschnitt 6) durch echte
 * OIDC-Authentifizierung + Rollen/Gruppen/ACLs ersetzt - der {@code X-Actor}-Header in
 * {@code TicketController}/{@code NoteController} ist derselbe provisorische Platzhalter.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
