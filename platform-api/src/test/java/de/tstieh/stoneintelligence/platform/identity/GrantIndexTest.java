package de.tstieh.stoneintelligence.platform.identity;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GrantIndexTest {

    private static final VaultId VAULT = VaultId.newId();
    private static final String[] NAMES = {"A", "B", "C", "Kunden", "Team"};
    private static final List<UUID> GROUPS = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    private static final List<String> PEOPLE = List.of("anna", "ben", "tom");

    private static String randomPath(Random random, int maxDepth) {
        var depth = random.nextInt(maxDepth + 1);
        var parts = new ArrayList<String>();
        for (var i = 0; i < depth; i++) {
            parts.add(NAMES[random.nextInt(NAMES.length)]);
        }
        return String.join("/", parts);
    }

    private static GrantScope randomScope(Random random) {
        return switch (random.nextInt(3)) {
            case 0 -> GrantScope.everyone();
            case 1 -> GrantScope.user(PEOPLE.get(random.nextInt(PEOPLE.size())));
            default -> GrantScope.group(GROUPS.get(random.nextInt(GROUPS.size())));
        };
    }

    private static Set<Permission> randomPermissions(Random random) {
        if (random.nextInt(5) == 0) {
            return null;
        }
        var permissions = EnumSet.noneOf(Permission.class);
        for (var permission : Permission.values()) {
            if (random.nextBoolean()) {
                permissions.add(permission);
            }
        }
        return permissions;
    }

    private static List<AccessGrant> randomGrants(Random random, int count) {
        var grants = new ArrayList<AccessGrant>();
        for (var i = 0; i < count; i++) {
            var target = random.nextBoolean()
                ? GrantTarget.folder(randomPath(random, 3))
                : GrantTarget.entry(NoteId.newId(), randomPath(random, 3) + "/n" + random.nextInt(3) + ".md");
            grants.add(new AccessGrant(UUID.randomUUID(), VAULT, target, randomScope(random), randomPermissions(random)));
        }
        return grants;
    }

    private static Membership randomMember(Random random) {
        var groups = new java.util.HashSet<UUID>();
        GROUPS.forEach(group -> {
            if (random.nextBoolean()) {
                groups.add(group);
            }
        });
        if (random.nextInt(6) == 0) {
            groups.clear();
        } else if (groups.isEmpty()) {
            groups.add(GROUPS.getFirst());
        }
        var vaultPermissions = randomPermissions(random);
        return new Membership(PEOPLE.get(random.nextInt(PEOPLE.size())), groups, vaultPermissions == null ? Set.of() : vaultPermissions);
    }

    // Der Index muss exakt so entscheiden wie die bewaehrte lineare Aufloesung - fuer beliebige Kombinationen.
    @Test
    void should_decideExactlyLikeTheLinearResolver_forRandomGrantsPathsAndPeople() {
        var random = new Random(20260929);
        for (var round = 0; round < 400; round++) {
            var grants = randomGrants(random, random.nextInt(25));
            var index = GrantIndex.of(grants);
            for (var probe = 0; probe < 40; probe++) {
                var who = randomMember(random);
                var base = randomPath(random, 4);
                var path = switch (random.nextInt(3)) {
                    case 0 -> base + "/";
                    case 1 -> (base.isEmpty() ? "" : base + "/") + "n" + random.nextInt(3) + ".md";
                    default -> base;
                };
                assertThat(index.resolve(who, path)).as("%s at %s with %s", who, path, grants)
                    .isEqualTo(AccessResolver.resolve(who, grants, path));
                assertThat(index.touched(path)).isEqualTo(AccessResolver.touchedByGrant(grants, path));
            }
        }
    }

    @Test
    void should_resolveQuickly_evenWithAHundredThousandGrants() {
        var grants = new ArrayList<AccessGrant>();
        for (var i = 0; i < 100_000; i++) {
            var target = i % 2 == 0
                ? GrantTarget.folder("Abteilung" + (i % 500) + "/Team" + i)
                : GrantTarget.entry(NoteId.newId(), "Abteilung" + (i % 500) + "/Notiz" + i + ".md");
            grants.add(new AccessGrant(UUID.randomUUID(), VAULT, target, GrantScope.user("p" + i), Set.of(Permission.READ)));
        }
        var index = GrantIndex.of(grants);
        var who = new Membership("p42", Set.of(GROUPS.getFirst()), Set.of(Permission.READ, Permission.WRITE));

        var start = System.nanoTime();
        for (var i = 0; i < 100_000; i++) {
            index.resolve(who, "Abteilung" + (i % 500) + "/Team" + i + "/Unterordner/Notiz.md");
        }
        var perCall = (System.nanoTime() - start) / 100_000;

        assertThat(perCall).as("ns per resolve").isLessThan(50_000);
        assertThat(index.resolve(who, "Abteilung42/Team42/x.md").permissions()).containsExactly(Permission.READ);
    }
}
