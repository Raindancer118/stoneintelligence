package de.tstieh.stoneintelligence.stoneai.ledger;

import java.time.Instant;
import java.util.List;

/**
 * One processed document. The provider list is deliberately part of the record: it is the log of
 * which external service a file's contents were sent to, which is exactly what you need when a
 * document turns out to have held personal data.
 */
public record LedgerEntry(String sha256,
                          String sourceFile,
                          Instant processedAt,
                          List<String> noteTitles,
                          List<String> providers,
                          int tokensUsed) {

    public LedgerEntry {
        noteTitles = List.copyOf(noteTitles);
        providers = List.copyOf(providers);
    }
}
