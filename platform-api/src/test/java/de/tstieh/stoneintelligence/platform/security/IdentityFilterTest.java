package de.tstieh.stoneintelligence.platform.security;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.platform.identity.BlockedSubjects;
import de.tstieh.stoneintelligence.platform.identity.FakeAuthorizationRepository;
import de.tstieh.stoneintelligence.platform.identity.FakePeopleDirectory;
import de.tstieh.stoneintelligence.platform.sync.relay.VaultAnnouncementService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;

class IdentityFilterTest {

    private final FakeAuthorizationRepository authorization = new FakeAuthorizationRepository();
    private final FakePeopleDirectory people = new FakePeopleDirectory();
    private final IdentitySync sync = new IdentitySync(authorization, people, new VaultAnnouncementService());
    private final BlockedSubjects blocked = new BlockedSubjects(() -> Set.of("left"), event -> { });
    private final VaultId vault = VaultId.newId();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletResponse request(String subject, Map<String, Object> claims, String groupsClaim) throws Exception {
        var builder = Jwt.withTokenValue("t").header("alg", "RS256").subject(subject).issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60));
        claims.forEach(builder::claim);
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(builder.build(), List.of(), subject));
        var response = new MockHttpServletResponse();
        new IdentityFilter(sync, blocked, groupsClaim, "name").doFilter(new MockHttpServletRequest(), response, new MockFilterChain());
        return response;
    }

    @Test
    void should_takeOverGroupsAndName_fromTheToken() throws Exception {
        authorization.linkIdpGroup(authorization.createGroup(vault, "Vertrieb").id(), "sales");

        var response = request("oid-ben", Map.of("name", "Ben Becker", "groups", List.of("sales", "other")), "groups");

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(authorization.membership(vault, "oid-ben").isMember()).isTrue();
        assertThat(people.displayNames(List.of("oid-ben"))).containsEntry("oid-ben", "Ben Becker");
    }

    @Test
    void should_readNestedGroupClaims_likeKeycloakRealmRoles() throws Exception {
        authorization.linkIdpGroup(authorization.createGroup(vault, "Vertrieb").id(), "sales");

        request("ben", Map.of("realm_access", Map.of("roles", List.of("sales"))), "realm_access.roles");

        assertThat(authorization.membership(vault, "ben").isMember()).isTrue();
    }

    @Test
    void should_fallBackToTheUsername_whenTheTokenHasNoName() throws Exception {
        request("oid-anna", Map.of("preferred_username", "anna"), "groups");

        assertThat(people.displayNames(List.of("oid-anna"))).containsEntry("oid-anna", "anna");
    }

    // Deaktiviert der IdP jemanden, endet der Zugriff sofort - nicht erst, wenn das Token ablaeuft.
    @Test
    void should_rejectDeactivatedAccounts_evenWithAValidToken() throws Exception {
        var response = request("left", Map.of(), "groups");

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).contains("invalid_token");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
