package de.tstieh.stoneintelligence.platform.security;

import java.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * JSON-Bodies der API werden vollstaendig in den Speicher gelesen; Tomcat begrenzt sie nicht. Datei-Uploads
 * (eigene Limits in FileService) und Multipart (Spring-Limit) sind ausgenommen, {@code /internal} ohnehin.
 */
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    private final long maxBytes;

    public RequestSizeLimitFilter(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        var path = request.getRequestURI();
        var contentType = request.getContentType();
        if (!(path.startsWith("/api/v1/") || path.startsWith("/scim/v2/"))) {
            return true;
        }
        if (contentType != null && contentType.toLowerCase(java.util.Locale.ROOT).startsWith("multipart/")) {
            return true;
        }
        return "PUT".equals(request.getMethod()) && path.matches("/api/v1/vaults/[^/]+/files/[^/]+/content");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > maxBytes) {
            response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "request body too large");
            return;
        }
        chain.doFilter(new LimitedRequest(request, maxBytes), response);
    }

    /** Fuer Anfragen ohne Content-Length (chunked): bricht beim Ueberschreiten ab. */
    private static final class LimitedRequest extends HttpServletRequestWrapper {

        private final long maxBytes;

        LimitedRequest(HttpServletRequest request, long maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            var delegate = super.getInputStream();
            return new ServletInputStream() {
                private long read;

                @Override
                public int read() throws IOException {
                    var b = delegate.read();
                    if (b >= 0 && ++read > maxBytes) {
                        throw new IOException("request body too large");
                    }
                    return b;
                }

                @Override
                public int read(byte[] buffer, int off, int len) throws IOException {
                    var n = delegate.read(buffer, off, len);
                    if (n > 0 && (read += n) > maxBytes) {
                        throw new IOException("request body too large");
                    }
                    return n;
                }

                @Override public boolean isFinished() { return delegate.isFinished(); }
                @Override public boolean isReady() { return delegate.isReady(); }
                @Override public void setReadListener(ReadListener listener) { delegate.setReadListener(listener); }
            };
        }
    }
}
