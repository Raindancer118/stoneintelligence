package de.raindancer118.stoneai.pipeline;

import de.raindancer118.stoneai.chunk.Chunk;
import de.raindancer118.stoneai.chunk.Chunker;
import de.raindancer118.stoneai.config.StoneAiConfig;
import de.raindancer118.stoneai.extract.ExtractionResult;
import de.raindancer118.stoneai.extract.ExtractionService;
import de.raindancer118.stoneai.extract.TopicPlanner;
import de.raindancer118.stoneai.extract.LlmClient;
import de.raindancer118.stoneai.ledger.LedgerEntry;
import de.raindancer118.stoneai.ledger.ProcessingLedger;
import de.raindancer118.stoneai.note.Consolidator;
import de.raindancer118.stoneai.note.DraftNote;
import de.raindancer118.stoneai.protection.ProtectionDecision;
import de.raindancer118.stoneai.protection.ProtectionPolicy;
import de.raindancer118.stoneai.source.DocumentKind;
import de.raindancer118.stoneai.source.DocumentLoaders;
import de.raindancer118.stoneai.source.EmptyDocumentException;
import de.raindancer118.stoneai.source.SourceDocument;
import de.raindancer118.stoneai.source.UnsupportedDocumentException;
import de.raindancer118.stoneai.vault.MocWriter;
import de.raindancer118.stoneai.vault.NoteStore;
import de.raindancer118.stoneai.vault.NoteWriteRefusedException;
import de.raindancer118.stoneai.vault.SourceNoteWriter;
import de.raindancer118.stoneai.vault.VaultIndex;
import de.raindancer118.stoneai.vault.VaultWriter;
import de.raindancer118.stoneai.vault.WriteResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The whole journey of one document: protection check, load, chunk, extract, consolidate, write.
 *
 * <p>The order matters. Protection is checked <em>before</em> anything is read into memory, so a
 * document a person marked private never reaches a provider — not even as a page image. The
 * ledger is consulted next, so a watch daemon that sees the same file twice does not pay for it
 * twice. Only then does any model get called.
 */
public final class IngestPipeline {

    /** Characters per chunk. Comfortably inside every current model's context, with room for the prompt. */
    private static final int CHUNK_CHARS = 10_000;
    private static final int CHUNK_OVERLAP = 400;

    private final StoneAiConfig config;
    private final LlmClient llm;
    private final ProcessingLedger ledger;
    private final Supplier<LocalDate> clock;
    private final boolean dryRun;
    private final boolean force;
    private final ProgressSink progress;

    public IngestPipeline(StoneAiConfig config, LlmClient llm, ProcessingLedger ledger,
                          Supplier<LocalDate> clock, boolean dryRun) {
        this(config, llm, ledger, clock, dryRun, false, ProgressSink.NONE);
    }

    private IngestPipeline(StoneAiConfig config, LlmClient llm, ProcessingLedger ledger,
                           Supplier<LocalDate> clock, boolean dryRun, boolean force, ProgressSink progress) {
        this.config = config;
        this.llm = llm;
        this.ledger = ledger;
        this.clock = clock;
        this.dryRun = dryRun;
        this.force = force;
        this.progress = progress;
    }

    /**
     * A pipeline for a vault it reaches only through a {@link NoteStore} (StoneIntelligence): the
     * caller decides what gets processed, so there is no ledger, nothing is moved and no
     * attachment is copied - the document is typically a temporary upload.
     */
    public static IngestPipeline hosted(StoneAiConfig config, LlmClient llm, Supplier<LocalDate> clock) {
        return new IngestPipeline(config, llm, null, clock, false, true, ProgressSink.NONE);
    }

    /** A pipeline that processes a document again even when the ledger has seen it. */
    public IngestPipeline force() {
        return new IngestPipeline(config, llm, ledger, clock, dryRun, true, progress);
    }

    /** A pipeline that reports each stage's progress (planning, per-chunk reading, merging) to {@code sink}. */
    public IngestPipeline withProgress(ProgressSink sink) {
        return new IngestPipeline(config, llm, ledger, clock, dryRun, force, sink);
    }

    public IngestReport ingest(Path file) throws IOException {
        if (ledger == null) {
            throw new IllegalStateException("a hosted pipeline writes through a NoteStore - use ingestInto");
        }
        return run(file, NoteStore.files());
    }

    /** Processes a document into the vault behind {@code store}; see {@link #hosted}. */
    public IngestReport ingestInto(Path file, NoteStore store) throws IOException {
        return run(file, store);
    }

    private IngestReport run(Path file, NoteStore store) throws IOException {
        boolean hosted = ledger == null;
        Path document = file.toAbsolutePath().normalize();
        RecordingLlmClient recorder = new RecordingLlmClient(llm);
        ProtectionPolicy protection = ProtectionPolicy.of(config, protectionRootFor(document));

        ProtectionDecision decision = protection.inspect(document);
        if (decision.isProtected()) {
            return IngestReport.skipped(document, "vor der KI geschützt — " + decision.reason());
        }

        SourceDocument source;
        try {
            source = DocumentLoaders.forConfig(config, (png, page) -> readPage(recorder, png, page), progress.scaled(5, 10))
                    .load(document);
        } catch (UnsupportedDocumentException | EmptyDocumentException e) {
            return IngestReport.skipped(document, e.getMessage());
        }

        if (!hosted && !force && ledger.isProcessed(source.sha256())) {
            return IngestReport.skipped(document, "bereits verarbeitet (Ledger) — mit --force erneut lesen");
        }

        // Headers, footers, page numbers and animation steps out; what is left is content.
        source = de.raindancer118.stoneai.source.PageCleaner.clean(source);
        List<Chunk> chunks = new Chunker(CHUNK_CHARS, CHUNK_OVERLAP).split(source);
        Path vaultRoot = config.vault().resolvedPath();
        VaultIndex index = VaultIndex.build(config, ProtectionPolicy.of(config, vaultRoot), store);

        // First decide what the document is about, then write exactly those notes.
        progress.report("Gliederung wird geplant", 10);
        TopicPlanner.Result planned = new TopicPlanner(config, recorder).plan(source, chunks, index.titles());

        progress.report("Text wird gelesen", 15);
        ExtractionResult extraction = new ExtractionService(config, recorder, progress.scaled(15, 75))
                .extract(chunks, planned.plan(), config.llm().tokenBudgetFor(source.lengthInPages()));

        progress.report("Notizen werden zusammengeführt", 78);
        List<DraftNote> notes = new Consolidator(config, recorder, progress.scaled(78, 95)).consolidate(extraction.concepts(),
                config.notes().maxNotesFor(source.lengthInPages()));
        progress.report("Notizen werden geschrieben", 96);

        VaultWriter writer = new VaultWriter(config, ProtectionPolicy.of(config, vaultRoot), clock, index, store);
        if (dryRun) {
            writer = writer.dryRun();
        }
        final VaultWriter noteWriter = writer;

        SourceNoteWriter sourceWriter = new SourceNoteWriter(config, clock, dryRun, store);
        String sourceLink = sourceWriter.linkFor(source);
        index.reserve(sourceWriter.fileFor(source));

        // Announce every note of this run before writing any of it, so a cross reference
        // resolves regardless of the order the notes happen to be written in.
        notes.forEach(note -> index.register(note.title(), note.aliases(), noteWriter.fileFor(note)));

        // The original goes in first: every page citation of the notes links into it.
        Path attachment = hosted ? storeOriginal(source, store) : copyAttachment(source);

        List<WriteResult> writes = new ArrayList<>();
        Set<String> links = new LinkedHashSet<>();
        for (DraftNote note : notes) {
            WriteResult result = noteWriter.write(note, source.sha256(), sourceLink, attachment);
            writes.add(result);
            if (result.wrote() || result.outcome() == WriteResult.Outcome.UNCHANGED) {
                links.add(index.linkTo(result.file(), vaultRoot));
            }
        }

        // What of the document is missing from the notes: image pages nobody could read, and
        // whatever the token budget did not reach.
        List<String> unread = new ArrayList<>();
        final SourceDocument read = source;
        source.unreadablePages().forEach(page -> unread.add(read.title() + ", S. " + page + " (Bild nicht lesbar)"));
        unread.addAll(extraction.unprocessed());

        if (config.notes().writeSourceNote()) {
            sourceWriter.write(source, List.copyOf(links), attachment, unread,
                    extraction.failures().stream().map(failure -> failure.provenanceLabel()).distinct().toList());
        }
        if (config.notes().writeMoc()) {
            new MocWriter(config, clock, dryRun, store).update(source, List.copyOf(links), sourceLink);
        }
        List<String> writtenTitles = writes.stream().filter(WriteResult::wrote)
                .map(write -> index.titleOf(write.file())).toList();

        if (!dryRun && !hosted) {
            ledger.record(new LedgerEntry(source.sha256(), document.toString(), Instant.now(),
                    writtenTitles, recorder.models(), extraction.tokensUsed() + planned.tokensUsed()));
            ledger.save();
            moveProcessed(document);
        }

        return new IngestReport(document, source.sha256(), source.title(), "", writes,
                extraction.failures(), source.skippedPages(), extraction.tokensUsed() + planned.tokensUsed(),
                extraction.budgetExhausted(), unread);
    }

    /** Reads a scanned page through the vision model. */
    private String readPage(RecordingLlmClient recorder, byte[] png, int pageNumber) {
        return recorder.readImage(png, de.raindancer118.stoneai.extract.VisionPrompts.ocr(
                pageNumber, config.llm().language())).text();
    }

    /**
     * The root the protection rules are relative to. For a file inside the vault that is the
     * vault; otherwise its own folder, so a {@code .stoneaiignore} next to an inbox still applies.
     */
    private Path protectionRootFor(Path document) {
        Path vault = config.vault().resolvedPath();
        return document.startsWith(vault) ? vault : document.getParent();
    }

    /**
     * A hosted run keeps the uploaded original in the vault, so the source note links to the
     * document itself. Text uploads are skipped - they would only sit beside their own notes as
     * a duplicate. A store that refuses (too large, no room) leaves the source note without it.
     */
    private Path storeOriginal(SourceDocument source, NoteStore store) throws IOException {
        if (!config.vault().copyAttachments() || dryRun || source.kind() != DocumentKind.PDF) {
            return null;
        }
        Path target = config.vault().attachmentsDir().resolve(source.file().getFileName().toString());
        try {
            return store.writeAttachment(target, Files.readAllBytes(source.file()));
        } catch (NoteWriteRefusedException refused) {
            return null;
        }
    }

    private Path copyAttachment(SourceDocument source) throws IOException {
        if (!config.vault().copyAttachments() || dryRun) {
            return null;
        }
        Path target = config.vault().attachmentsDir().resolve(source.file().getFileName());
        if (source.file().startsWith(config.vault().resolvedPath())) {
            return source.file();
        }
        Files.createDirectories(target.getParent());
        Files.copy(source.file(), target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    private void moveProcessed(Path document) throws IOException {
        if (!config.ingest().moveProcessed() || !document.startsWith(config.ingest().inboxDir())) {
            return;
        }
        Path target = config.vault().resolvedPath()
                .resolve(config.ingest().processedFolder())
                .resolve(document.getFileName());
        Files.createDirectories(target.getParent());
        Files.move(document, target, StandardCopyOption.REPLACE_EXISTING);
    }
}
