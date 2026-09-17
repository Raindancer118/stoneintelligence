package de.raindancer118.stoneintelligence.platform.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.core.convert.converter.Converter;

/**
 * OIDC-Durchsetzung (Plan.md Abschnitt 6, Phase 3; ADR 0006 Punkt 6): loest die vorherige
 * PROVISORISCHE {@code permitAll()}-Konfiguration ab. Der Identity-Provider ist Authentik
 * ({@code STONEINTELLIGENCE_OIDC_ISSUER_URI}, s. Project.md) - Spring validiert eingehende
 * Bearer-Tokens gegen dessen JWK-Set (spring-boot-starter-oauth2-resource-server), kein eigener
 * Login-Endpunkt in platform-api noetig.
 *
 * <p>Der Principal-Name wird bewusst NICHT auf den JWT-{@code sub}-Claim (eine UUID bei
 * Authentik) gemappt, sondern auf {@code preferred_username} - das ist derselbe Subject-String,
 * den {@code AuthorizationRepository}/{@code PathRules} schon vorher fuer Rollen/Gruppen/ACLs
 * verwendet haben (z. B. "tom"), damit bestehende Zuordnungen ohne Migration gueltig bleiben.
 *
 * <p>{@code /ws/sync} bleibt bewusst von der OIDC-Pflicht ausgenommen: der WebSocket-Handshake
 * traegt kein Bearer-Token, sondern das kurzlebige Single-Use-Ticket aus {@code TicketController}
 * als Query-Parameter - das Ticket selbst ist der Auth-Nachweis fuer genau diese eine
 * Verbindung (die Ticket-AUSSTELLUNG wiederum verlangt sehr wohl OIDC, s. dort).
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                .requestMatchers("/ws/sync").permitAll()
                .anyRequest().authenticated())
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
        return http.build();
    }

    private Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter() {
        var converter = new JwtAuthenticationConverter();
        converter.setPrincipalClaimName("preferred_username");
        return converter;
    }
}
