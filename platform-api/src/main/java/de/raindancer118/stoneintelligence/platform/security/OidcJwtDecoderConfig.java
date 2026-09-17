package de.raindancer118.stoneintelligence.platform.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;

/**
 * Baut den produktiven {@link JwtDecoder} explizit selbst statt Spring Boots eigene
 * {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}-Autoconfiguration zu nutzen:
 * deren Bean-Methode wuerde bei fehlender/leerer Property beim Context-Start sofort eine
 * ungueltige Issuer-URI aufloesen wollen und crashen - {@link ConditionalOnProperty} sorgt
 * dagegen dafuer, dass diese Bean in Testkontexten (ohne {@code STONEINTELLIGENCE_OIDC_ISSUER_URI})
 * gar nicht erst existiert; dort tritt stattdessen der Test-Decoder aus {@code TestJwtSupport} an
 * ihre Stelle.
 */
@Configuration
@ConditionalOnProperty(name = "STONEINTELLIGENCE_OIDC_ISSUER_URI")
public class OidcJwtDecoderConfig {

    @Bean
    public JwtDecoder jwtDecoder(@Value("${STONEINTELLIGENCE_OIDC_ISSUER_URI}") String issuerUri) {
        return JwtDecoders.fromIssuerLocation(issuerUri);
    }
}
