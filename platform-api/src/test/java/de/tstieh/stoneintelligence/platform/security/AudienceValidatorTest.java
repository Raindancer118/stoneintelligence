package de.tstieh.stoneintelligence.platform.security;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;

class AudienceValidatorTest {

    private static Jwt token(Object audience) {
        var builder = Jwt.withTokenValue("t").header("alg", "none").subject("tom")
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (audience != null) {
            builder.claim("aud", audience);
        }
        return builder.build();
    }

    @Test
    void should_acceptTokensForThisAudience() {
        assertThat(new AudienceValidator("stone").validate(token(List.of("other", "stone"))).hasErrors()).isFalse();
    }

    @Test
    void should_rejectTokensForOtherOrNoAudience() {
        assertThat(new AudienceValidator("stone").validate(token(List.of("other"))).hasErrors()).isTrue();
        assertThat(new AudienceValidator("stone").validate(token(null)).hasErrors()).isTrue();
    }
}
