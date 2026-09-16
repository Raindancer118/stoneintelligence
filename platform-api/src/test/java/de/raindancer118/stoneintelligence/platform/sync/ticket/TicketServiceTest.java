package de.raindancer118.stoneintelligence.platform.sync.ticket;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import de.raindancer118.stoneintelligence.domain.id.NoteId;
import de.raindancer118.stoneintelligence.domain.id.VaultId;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kurzlebige Single-Use-Tickets fuer WebSocket-Auth (Plan.md Abschnitt 3, "positiv zu
 * uebernehmendes Muster" aus stonesync) - der Obsidian-WS-Client kann keine Custom-Header
 * senden, das Ticket wird deshalb als Query-Parameter uebergeben und darf nur einmal einlösbar
 * sein.
 */
class TicketServiceTest {

    private static final VaultId VAULT_ID = VaultId.newId();
    private static final NoteId NOTE_ID = NoteId.newId();
    private static final Duration TTL = Duration.ofSeconds(30);

    private final Instant now = Instant.parse("2026-09-20T10:00:00Z");

    private TicketService serviceAt(Instant instant) {
        return new TicketService(new MutableClock(instant, ZoneOffset.UTC), TTL, new InMemoryTicketStore());
    }

    @Nested
    class Issue {

        @Test
        void should_produceUniqueTokens_when_issuedRepeatedly() {
            var service = serviceAt(now);

            var first = service.issue(VAULT_ID, NOTE_ID, "tom");
            var second = service.issue(VAULT_ID, NOTE_ID, "tom");

            assertThat(first.token()).isNotEqualTo(second.token());
        }
    }

    @Nested
    class Redeem {

        @Test
        void should_returnClaims_when_tokenIsFreshAndUnused() {
            var service = serviceAt(now);
            var ticket = service.issue(VAULT_ID, NOTE_ID, "tom");

            var claims = service.redeem(ticket.token());

            assertThat(claims).isPresent();
            assertThat(claims.get().vaultId()).isEqualTo(VAULT_ID);
            assertThat(claims.get().noteId()).isEqualTo(NOTE_ID);
            assertThat(claims.get().actor()).isEqualTo("tom");
        }

        @Test
        void should_returnEmpty_when_tokenIsUnknown() {
            var service = serviceAt(now);

            assertThat(service.redeem("does-not-exist")).isEmpty();
        }

        @Test
        void should_returnEmptyOnSecondRedeem_when_tokenAlreadyUsedOnce() {
            var service = serviceAt(now);
            var ticket = service.issue(VAULT_ID, NOTE_ID, "tom");

            service.redeem(ticket.token());
            var secondAttempt = service.redeem(ticket.token());

            assertThat(secondAttempt).isEmpty();
        }

        @Test
        void should_returnEmpty_when_ticketExpired() {
            var clock = new MutableClock(now, ZoneOffset.UTC);
            var service = new TicketService(clock, TTL, new InMemoryTicketStore());
            var ticket = service.issue(VAULT_ID, NOTE_ID, "tom");

            clock.advanceTo(now.plus(TTL).plusSeconds(1));

            assertThat(service.redeem(ticket.token())).isEmpty();
        }

        @Test
        void should_keepTicketsIsolated_when_issuedForDifferentNotes() {
            var service = serviceAt(now);
            var otherNote = NoteId.newId();

            var ticketForNote = service.issue(VAULT_ID, NOTE_ID, "tom");
            var ticketForOtherNote = service.issue(VAULT_ID, otherNote, "tom");

            assertThat(service.redeem(ticketForNote.token()).get().noteId()).isEqualTo(NOTE_ID);
            assertThat(service.redeem(ticketForOtherNote.token()).get().noteId()).isEqualTo(otherNote);
        }
    }
}
