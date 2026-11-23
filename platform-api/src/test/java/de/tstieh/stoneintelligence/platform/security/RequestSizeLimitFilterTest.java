package de.tstieh.stoneintelligence.platform.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class RequestSizeLimitFilterTest {

    private final RequestSizeLimitFilter filter = new RequestSizeLimitFilter(1024);

    private MockHttpServletResponse run(String method, String uri, String contentType, int declared) throws Exception {
        var request = new MockHttpServletRequest(method, uri);
        request.setContent(new byte[declared]);
        request.setContentType(contentType);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        if (chain.getRequest() != null) {
            response.setHeader("X-Passed", "true");
        }
        return response;
    }

    @Test
    void should_rejectOversizedJsonBodies_with413() throws Exception {
        var response = run("POST", "/api/v1/vaults/x/notes", "application/json", 2048);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getHeader("X-Passed")).isNull();
    }

    @Test
    void should_letSmallBodiesPass() throws Exception {
        assertThat(run("POST", "/api/v1/vaults/x/notes", "application/json", 100).getHeader("X-Passed")).isEqualTo("true");
    }

    @Test
    void should_exemptFileUploadsAndMultipart() throws Exception {
        assertThat(run("PUT", "/api/v1/vaults/x/files/y/content", "application/pdf", 5000).getHeader("X-Passed")).isEqualTo("true");
        assertThat(run("POST", "/api/v1/vaults/x/ai/jobs", "multipart/form-data; boundary=a", 5000).getHeader("X-Passed")).isEqualTo("true");
    }
}
