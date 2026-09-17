package de.raindancer118.stoneintelligence.platform.ratelimit;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitInterceptorTest {

    @Test
    void should_letRequestThrough_when_underLimit() {
        var interceptor = new RateLimitInterceptor(new RateLimiter(Clock.systemUTC(), 5, 1.0));
        var request = new MockHttpServletRequest();
        request.addHeader("X-Actor", "tom");
        var response = new MockHttpServletResponse();

        var passed = interceptor.preHandle(request, response, new Object());

        assertThat(passed).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void should_reject_withRetryAfterHeader_when_limitExceeded() {
        var interceptor = new RateLimitInterceptor(new RateLimiter(Clock.systemUTC(), 1, 1.0));
        var request = new MockHttpServletRequest();
        request.addHeader("X-Actor", "tom");

        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());
        var response = new MockHttpServletResponse();
        var passed = interceptor.preHandle(request, response, new Object());

        assertThat(passed).isFalse();
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isNotNull();
    }

    @Test
    void should_isolateLimits_perActor() {
        var interceptor = new RateLimitInterceptor(new RateLimiter(Clock.systemUTC(), 1, 1.0));
        var tomRequest = new MockHttpServletRequest();
        tomRequest.addHeader("X-Actor", "tom");
        interceptor.preHandle(tomRequest, new MockHttpServletResponse(), new Object());

        var aliceRequest = new MockHttpServletRequest();
        aliceRequest.addHeader("X-Actor", "alice");
        var passed = interceptor.preHandle(aliceRequest, new MockHttpServletResponse(), new Object());

        assertThat(passed).isTrue();
    }

    @Test
    void should_fallBackToRemoteAddress_when_actorHeaderMissing() {
        var interceptor = new RateLimitInterceptor(new RateLimiter(Clock.systemUTC(), 1, 1.0));
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");

        var passed = interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertThat(passed).isTrue();
    }
}
