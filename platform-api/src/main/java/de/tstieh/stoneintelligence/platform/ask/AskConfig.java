package de.tstieh.stoneintelligence.platform.ask;

import java.time.Instant;
import de.tstieh.stoneintelligence.platform.ai.AiAuditRecorder;
import de.tstieh.stoneintelligence.platform.ai.AiJobRepository;
import de.tstieh.stoneintelligence.platform.ai.AiJobService;
import de.tstieh.stoneintelligence.platform.ai.AiServiceDirectory;
import de.tstieh.stoneintelligence.platform.ai.AiWriteService;
import de.tstieh.stoneintelligence.platform.ai.LinkingService;
import de.tstieh.stoneintelligence.platform.ai.NoteEmbeddingRepository;
import de.tstieh.stoneintelligence.platform.audit.AuditService;
import de.tstieh.stoneintelligence.platform.search.NoteTextIndex;
import de.tstieh.stoneintelligence.platform.vault.NoteRepository;
import de.tstieh.stoneintelligence.platform.vault.VaultAccessGuard;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration
public class AskConfig {

    /** Kosinus-Untergrenze fuer semantische Treffer; mit multilingual-e5-small liegt Fremdes bei etwa 0,75 bis 0,80. */
    @Bean
    public AskRetrieval askRetrieval(NoteTextIndex texts, NoteEmbeddingRepository embeddings, NoteRepository notes, VaultAccessGuard access,
                                     @Lazy AiWriteService ai, @Value("${stoneintelligence.ask.min-similarity:0.80}") double minSimilarity) {
        return new AskRetrieval(texts, embeddings, notes, access, ai::mayProcess, minSimilarity);
    }

    @Bean
    public AskService askService(QuestionRepository questions, AiJobService jobs, AiJobRepository jobRepository, AiServiceDirectory services,
                                 AskRetrieval retrieval, VaultAccessGuard access, NoteRepository notes, LinkingService linking,
                                 AuditService audit) {
        AiAuditRecorder recorder = audit::record;
        return new AskService(questions, jobs, jobRepository, services, retrieval, access, notes,
            vaultId -> linking.internalSettings(vaultId).aiConsents(), recorder, Instant::now);
    }
}
