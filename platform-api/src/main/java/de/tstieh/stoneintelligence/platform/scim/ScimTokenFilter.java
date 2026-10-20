package de.tstieh.stoneintelligence.platform.scim;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authentifiziert den IdP an {@code /scim/v2/**} ueber ein Bearer-Token (RFC 7644 Abschnitt 2).
 * Mehrere Tokens, durch Komma getrennt, erlauben einen Wechsel ohne Ausfall. Verglichen werden
 * SHA-256-Hashes in konstanter Zeit, damit weder Inhalt noch Laenge eines Tokens durchsickern.
 */
public class ScimTokenFilter extends OncePerRequestFilter {

    public static final String ROLE = "SCIM_CLIENT";
    static final int MIN_TOKEN_LENGTH = 32;

    private final List<byte[]> expected;

    public ScimTokenFilter(String tokens) {
        this.expected = tokens == null ? List.of() : Arrays.stream(tokens.split(","))
            .map(String::trim)
            .filter(token -> token.length() >= MIN_TOKEN_LENGTH)
            .map(ScimTokenFilter::sha256)
            .toList();
    }

    public boolean enabled() {
        return !expected.isEmpty();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            var presented = sha256(header.substring(7).trim());
            var match = false;
            for (var token : expected) {
                match |= MessageDigest.isEqual(token, presented);
            }
            if (match) {
                SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    "scim", null, List.of(new SimpleGrantedAuthority("ROLE_" + ROLE))));
            }
        }
        chain.doFilter(request, response);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
