package de.tstieh.stoneintelligence.platform.ratelimit;

import java.time.Clock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitInterceptorTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String actor) {
        var token = new TestingAuthenticationToken(actor, null);
        token.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    @Test
    void should_letAuthenticatedRequestsThrough_regardlessOfLimit() {
        // Ein Vault-weiter Sync (viele Notizen, je zwei Requests) ist normale legitime Nutzung,
        // kein Missbrauch - der Token-Bucket-Limiter existiert primaer gegen unauthentifizierte
        // Anfragen (Plan.md/RateLimitInterceptor-Doku), authentifizierte Nutzer sind bereits
        // ueber OIDC identifiziert und auditierbar.
        var interceptor = new RateLimitInterceptor(new RateLimiter(Clock.systemUTC(), 1, 0.0));
        authenticateAs("tom");

        for (var i = 0; i < 10; i++) {
            var response = new MockHttpServletResponse();
            var passed = interceptor.preHandle(new MockHttpServletRequest(), response, new Object());
            assertThat(passed).isTrue();
            assertThat(response.getStatus()).isEqualTo(200);
        }
    }

    @Test
    void should_reject_withRetryAfterHeader_when_unauthenticatedLimitExceeded() {
        var interceptor = new RateLimitInterceptor(new RateLimiter(Clock.systemUTC(), 1, 1.0));
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");

        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());
        var response = new MockHttpServletResponse();
        var passed = interceptor.preHandle(request, response, new Object());

        assertThat(passed).isFalse();
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isNotNull();
    }

    @Test
    void should_isolateLimits_perRemoteAddress() {
        var interceptor = new RateLimitInterceptor(new RateLimiter(Clock.systemUTC(), 1, 1.0));
        var first = new MockHttpServletRequest();
        first.setRemoteAddr("127.0.0.1");
        interceptor.preHandle(first, new MockHttpServletResponse(), new Object());

        var second = new MockHttpServletRequest();
        second.setRemoteAddr("127.0.0.2");
        var passed = interceptor.preHandle(second, new MockHttpServletResponse(), new Object());

        assertThat(passed).isTrue();
    }

    @Test
    void should_fallBackToRemoteAddress_when_notAuthenticated() {
        var interceptor = new RateLimitInterceptor(new RateLimiter(Clock.systemUTC(), 1, 1.0));
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");

        var passed = interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertThat(passed).isTrue();
    }
}
