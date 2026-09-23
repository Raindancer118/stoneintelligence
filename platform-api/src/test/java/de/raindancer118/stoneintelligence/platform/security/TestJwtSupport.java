package de.raindancer118.stoneintelligence.platform.security;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Test-only OIDC-Unterstuetzung fuer jeden {@code @SpringBootTest}: erzeugt einmalig ein
 * RSA-Test-Schluesselpaar und stellt den einzigen {@link JwtDecoder} im Testkontext (die
 * produktive {@code OidcJwtDecoderConfig}-Bean existiert dort nicht, s. deren Javadoc), damit
 * kein Test einen echten Authentik-Issuer erreichen muss. {@link #signedJwtFor(String)}
 * signiert damit ein echtes, gueltiges JWT fuer reale HTTP-Aufrufe in IT-Tests.
 */
@TestConfiguration
public class TestJwtSupport {

    private static final KeyPair KEY_PAIR = generateKeyPair();

    private static KeyPair generateKeyPair() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEY_PAIR.getPublic()).build();
    }

    public static String signedJwtFor(String preferredUsername) {
        return signedJwtFor(preferredUsername, null);
    }

    /** Wie {@link #signedJwtFor(String)}, zusaetzlich mit {@code email}-Claim (wie Authentik ihn ausstellt). */
    public static String signedJwtFor(String preferredUsername, String email) {
        try {
            var claims = new JWTClaimsSet.Builder()
                .subject(preferredUsername)
                .claim("preferred_username", preferredUsername)
                .claim("email", email)
                .claim("email_verified", email != null)
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .build();
            var signedJwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
            signedJwt.sign(new RSASSASigner((RSAPrivateKey) KEY_PAIR.getPrivate()));
            return signedJwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
