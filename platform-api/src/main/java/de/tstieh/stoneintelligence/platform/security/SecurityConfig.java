package de.tstieh.stoneintelligence.platform.security;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.core.convert.converter.Converter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * OIDC-Durchsetzung (Plan.md Abschnitt 6, Phase 3; ADR 0006 Punkt 6): loest die vorherige
 * PROVISORISCHE {@code permitAll()}-Konfiguration ab. Der Identity-Provider ist Authentik
 * ({@code STONEINTELLIGENCE_OIDC_ISSUER_URI}, s. Project.md) - Spring validiert eingehende
 * Bearer-Tokens gegen dessen JWK-Set (spring-boot-starter-oauth2-resource-server), kein eigener
 * Login-Endpunkt in platform-api noetig.
 *
 * <p>Der Principal-Name wird bewusst NICHT auf den JWT-{@code sub}-Claim (eine UUID bei
 * Authentik) gemappt, sondern auf {@code preferred_username} - das ist derselbe Subject-String,
 * den {@code AuthorizationRepository} und die Freigaben schon vorher fuer Rollen/Gruppen/ACLs
 * verwendet haben (z. B. "tom"), damit bestehende Zuordnungen ohne Migration gueltig bleiben.
 *
 * <p>{@code /ws/sync} bleibt bewusst von der OIDC-Pflicht ausgenommen: der WebSocket-Handshake
 * traegt kein Bearer-Token, sondern das kurzlebige Single-Use-Ticket aus {@code TicketController}
 * als Query-Parameter - das Ticket selbst ist der Auth-Nachweis fuer genau diese eine
 * Verbindung (die Ticket-AUSSTELLUNG wiederum verlangt sehr wohl OIDC, s. dort).
 */
@Configuration
public class SecurityConfig {

    /**
     * Ohne CORS-Konfiguration blockt der Browser jeden Cross-Origin-Request des
     * Webapp-Dashboards (kb.tstieh.de) schon vor Spring Security - das JS sieht dafuer nur ein
     * generisches "Failed to fetch" ohne jeden HTTP-Statuscode (live beobachtet, bevor diese
     * Konfiguration existierte). Erlaubt ist die Webapp dieser Instanz ({@code STONEINTELLIGENCE_WEBAPP_URL},
     * dieselbe Adresse wie in den Einladungslinks - selbst gehostet eine andere) und `localhost:5173`
     * fuer `npm run dev` gegen die deployte API.
     */
    private final List<String> allowedOrigins;
    /**
     * Welcher Claim die Person bezeichnet (ADR 0013). Fuer Firmen-IdPs empfohlen: ein unveraenderlicher
     * wie {@code sub} oder {@code oid}; {@code preferred_username} aendert sich dort z. B. bei Heirat.
     */
    private final String principalClaim;
    /** Darf ein Pfad sein ({@code realm_access.roles}). */
    private final String groupsClaim;
    private final String nameClaim;

    public SecurityConfig(@Value("${STONEINTELLIGENCE_WEBAPP_URL:https://kb.tstieh.de}") String webappUrl,
                          @Value("${STONEINTELLIGENCE_OIDC_PRINCIPAL_CLAIM:preferred_username}") String principalClaim,
                          @Value("${STONEINTELLIGENCE_OIDC_GROUPS_CLAIM:groups}") String groupsClaim,
                          @Value("${STONEINTELLIGENCE_OIDC_NAME_CLAIM:name}") String nameClaim) {
        this.allowedOrigins = List.of(originOf(webappUrl), "http://localhost:5173");
        this.principalClaim = principalClaim;
        this.groupsClaim = groupsClaim;
        this.nameClaim = nameClaim;
    }

    /** CORS vergleicht nur Schema, Host und Port - ein Pfad oder Schraegstrich in der URL wuerde nie passen. */
    private static String originOf(String url) {
        var uri = java.net.URI.create(url.trim());
        return uri.getScheme() + "://" + uri.getRawAuthority();
    }

    /**
     * {@code /internal/**} gehoert allein dem KI-Worker (ADR 0008): eigene Kette mit Service-Token,
     * ohne OIDC - ein Personen-Token oeffnet sie nicht, das Worker-Token keine andere Route.
     */
    @Bean
    @org.springframework.core.annotation.Order(1)
    public SecurityFilterChain internalWorkerChain(HttpSecurity http,
            de.tstieh.stoneintelligence.platform.ai.AiWorkerTokenFilter workerToken) throws Exception {
        http
            .securityMatcher("/internal/**")
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(
                org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
            .addFilterBefore(workerToken,
                org.springframework.security.web.authentication.AnonymousAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth.anyRequest()
                .hasRole(de.tstieh.stoneintelligence.platform.ai.AiWorkerTokenFilter.ROLE))
            .exceptionHandling(errors -> errors.authenticationEntryPoint(
                new org.springframework.security.web.authentication.HttpStatusEntryPoint(org.springframework.http.HttpStatus.UNAUTHORIZED)));
        return http.build();
    }

    /**
     * {@code /scim/v2/**} gehoert allein dem Identitaetsanbieter (ADR 0013): eigenes Bearer-Token, kein OIDC.
     * Antworten ohne gueltiges Token im SCIM-Fehlerformat, damit der IdP die Ursache anzeigt.
     */
    @Bean
    @org.springframework.core.annotation.Order(0)
    public SecurityFilterChain scimChain(HttpSecurity http,
            de.tstieh.stoneintelligence.platform.scim.ScimTokenFilter scimToken) throws Exception {
        http
            .securityMatcher("/scim/v2/**")
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(
                org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
            .addFilterBefore(scimToken,
                org.springframework.security.web.authentication.AnonymousAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth.anyRequest()
                .hasRole(de.tstieh.stoneintelligence.platform.scim.ScimTokenFilter.ROLE))
            .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, denied) -> {
                response.setStatus(org.springframework.http.HttpStatus.UNAUTHORIZED.value());
                response.setHeader("WWW-Authenticate", "Bearer realm=\"scim\"");
                response.setContentType("application/scim+json");
                response.getWriter().write("{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:Error\"],"
                    + "\"status\":\"401\",\"detail\":\"A valid SCIM bearer token is required\"}");
            }));
        return http.build();
    }

    @Bean
    @org.springframework.core.annotation.Order(2)
    public SecurityFilterChain securityFilterChain(HttpSecurity http, IdentitySync identity,
            de.tstieh.stoneintelligence.platform.identity.BlockedSubjects blocked) throws Exception {
        http
            .addFilterAfter(new IdentityFilter(identity, blocked, groupsClaim, nameClaim),
                org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter.class)
            .csrf(csrf -> csrf.disable())
            .cors(Customizer.withDefaults())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                .requestMatchers("/ws/sync").permitAll()
                // Fehlerweiterleitung (z. B. ein 403 aus /internal) - sonst machte die OIDC-Pflicht
                // aus jedem Fehlerstatus eines Aufrufers ohne JWT ein 401.
                .requestMatchers("/error").permitAll()
                // Eingeladene ohne Konto muessen sehen koennen, wozu sie eingeladen wurden -
                // nur GET, Annehmen verlangt eine Anmeldung.
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/invitations/*").permitAll()
                .anyRequest().authenticated())
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        var configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Operation-Id", "If-Match"));
        // Datei-Downloads (ADR 0009): Fassung, Hash und Name muss das Dashboard lesen koennen.
        configuration.setExposedHeaders(List.of("ETag", "X-Content-SHA256", "X-Current-Revision", "Content-Disposition"));
        configuration.setMaxAge(3600L);

        // /ws/sync braucht KEINE CORS-Durchsetzung: der Handshake ist ueber das Single-Use-Ticket
        // selbst abgesichert (s. TicketHandshakeInterceptor), nicht ueber Origin+Credentials wie
        // ein normaler Browser-Request. `.requestMatchers("/ws/sync").permitAll()` (s. oben)
        // regelt nur die AUTORISIERUNG - Spring Securitys CorsFilter ist eine fruehere, davon
        // komplett getrennte Pruefung und lehnt eine ECHTE (nicht nur Preflight-)Anfrage mit
        // einem nicht erlaubten Origin pauschal mit 403 ab. Obsidian Desktop (Electron) schickt
        // einen Origin, der nie in allowedOrigins (nur die Webapp) stehen kann - live beobachtet:
        // jeder Verbindungsversuch von Obsidian Desktop scheiterte mit 403, BEVOR das Ticket
        // ueberhaupt geprueft wurde.
        var wsConfiguration = new CorsConfiguration();
        wsConfiguration.setAllowedOriginPatterns(List.of("*"));
        wsConfiguration.setAllowedMethods(List.of("GET"));

        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/ws/sync", wsConfiguration);
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    private Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter() {
        var converter = new JwtAuthenticationConverter();
        converter.setPrincipalClaimName(principalClaim);
        return converter;
    }
}
