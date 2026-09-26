package de.tstieh.stoneintelligence.worker.ingest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import de.tstieh.stoneintelligence.stoneai.vault.IndexedNote;
import de.tstieh.stoneintelligence.worker.embed.Embedder;
import de.tstieh.stoneintelligence.worker.embed.NoteChunker;
import de.tstieh.stoneintelligence.worker.link.MentionFinder;
import de.tstieh.stoneintelligence.worker.platform.ClaimedJob;
import de.tstieh.stoneintelligence.worker.platform.EmbeddedChunk;
import de.tstieh.stoneintelligence.worker.platform.EmbeddingState;
import de.tstieh.stoneintelligence.worker.platform.ListedNote;
import de.tstieh.stoneintelligence.worker.platform.PlatformApi;
import de.tstieh.stoneintelligence.worker.platform.ProposedLink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ein Verlinkungslauf (ADR 0012). Nichts davon verlaesst den Server:
 * <ol>
 *   <li>Index: neue oder geaenderte Notizen bekommen Abschnitts-Vektoren (lokales Modell) - Grundlage
 *   fuer Stufe 2 und "Aehnliche Notizen".</li>
 *   <li>Stufe 1: nennt eine Notiz Titel oder Alias einer anderen woertlich, wird die Stelle verlinkt.</li>
 *   <li>Stufe 2 (Modus SEMANTIC): inhaltlich sehr aehnliche Notizen kommen unter "Verwandt" - nicht
 *   Beinahe-Duplikate, und nie ein Paar, das schon einmal verlinkt war (das merkt sich der Server).</li>
 * </ol>
 * Welche Notizen der Lauf sieht und ob in Notizen von Menschen verlinkt werden darf, entscheidet
 * platform-api; der Server setzt auch die Links selbst.
 */
final class LinkingRun {

    private static final Logger LOG = LoggerFactory.getLogger(LinkingRun.class);
    private static final int SIMILAR_PER_NOTE = 10;

    /** Ab {@code link} aehnlich genug fuer einen Link, ab {@code duplicate} so gleich, dass es eher eine Kopie ist. */
    record Thresholds(double link, double duplicate) {

        /** Gemessen mit multilingual-e5-small an Notizen: gleiches Thema 0,83-0,90, fremdes 0,75-0,80 (s. LinkingCalibrationTest). Per Umgebung anpassbar. */
        static Thresholds from(Map<String, String> env) {
            return new Thresholds(number(env.get("STONEAI_LINK_SIMILARITY"), 0.86), number(env.get("STONEAI_LINK_DUPLICATE"), 0.97));
        }

        private static double number(String value, double fallback) {
            try {
                return value == null || value.isBlank() ? fallback : Double.parseDouble(value);
            } catch (NumberFormatException invalid) {
                return fallback;
            }
        }
    }

    private final PlatformApi platform;
    private final Embedder embedder;
    private final Thresholds thresholds;

    /** Ohne Modell: nur Stufe 1. */
    LinkingRun(PlatformApi platform) {
        this(platform, null, new Thresholds(1.1, 1.1));
    }

    LinkingRun(PlatformApi platform, Embedder embedder, Thresholds thresholds) {
        this.platform = platform;
        this.embedder = embedder;
        this.thresholds = thresholds;
    }

    void run(ClaimedJob job) {
        platform.progress(job.jobId(), "Notizen werden gelesen", 5);
        var notes = platform.notes(job.vaultId(), job.changeSetId()).stream()
            .filter(note -> !note.isFile() && note.path().toLowerCase(java.util.Locale.ROOT).endsWith(".md"))
            .toList();
        var texts = new LinkedHashMap<String, String>();
        var titles = new LinkedHashMap<String, String>();
        var names = new LinkedHashMap<String, List<String>>();
        for (var i = 0; i < notes.size(); i++) {
            var note = notes.get(i);
            var text = platform.read(job.vaultId(), job.changeSetId(), note.noteId());
            texts.put(note.noteId(), text);
            var indexed = IndexedNote.fromContent(Path.of(note.path()), text);
            titles.put(note.noteId(), indexed.title());
            names.put(note.noteId(), Stream.concat(Stream.of(indexed.title()), indexed.aliases().stream()).distinct().toList());
            progress(job, "Notizen werden gelesen", i, notes.size(), 5, 25);
        }

        var embedded = index(job, notes, texts, titles);

        var finder = new MentionFinder(names);
        var links = 0;
        var changed = new HashSet<String>();
        for (var i = 0; i < notes.size(); i++) {
            var note = notes.get(i);
            var mentions = finder.find(note.noteId(), texts.get(note.noteId()));
            if (!mentions.isEmpty()) {
                var applied = platform.link(job.vaultId(), job.changeSetId(), note.noteId(),
                    mentions.stream().map(mention -> new ProposedLink(mention.targetNoteId(), mention.anchor(), false)).toList());
                links += applied;
                if (applied > 0) {
                    changed.add(note.noteId());
                }
            }
            progress(job, "Wörtliche Nennungen werden verlinkt", i, notes.size(), 60, 75);
        }

        if (embedded && "SEMANTIC".equals(mode(job))) {
            for (var i = 0; i < notes.size(); i++) {
                var note = notes.get(i);
                var proposals = new ArrayList<ProposedLink>();
                for (var similar : platform.similar(job.vaultId(), job.changeSetId(), note.noteId(), SIMILAR_PER_NOTE)) {
                    if (similar.similarity() >= thresholds.link() && similar.similarity() < thresholds.duplicate()
                            && titles.containsKey(similar.noteId())) {
                        proposals.add(new ProposedLink(similar.noteId(), titles.get(similar.noteId()), true));
                    }
                }
                if (!proposals.isEmpty()) {
                    var applied = platform.link(job.vaultId(), job.changeSetId(), note.noteId(), proposals);
                    links += applied;
                    if (applied > 0) {
                        changed.add(note.noteId());
                    }
                }
                progress(job, "Ähnliche Inhalte werden verlinkt", i, notes.size(), 75, 98);
            }
        }
        platform.progress(job.jobId(), summary(links, changed.size()), 100);
        platform.complete(job.jobId());
    }

    /** Vektoren fuer Neues und Geaendertes; liefert, ob es einen Index gibt (Modell vorhanden). */
    private boolean index(ClaimedJob job, List<ListedNote> notes, Map<String, String> texts, Map<String, String> titles) {
        if (embedder == null) {
            return false;
        }
        var known = new LinkedHashMap<String, EmbeddingState>();
        platform.embeddingStates(job.vaultId(), job.changeSetId()).forEach(state -> known.put(state.noteId(), state));
        var stale = notes.stream().filter(note -> {
            var state = known.get(note.noteId());
            return state == null || !embedder.model().equals(state.model()) || !sha256(texts.get(note.noteId())).equals(state.contentHash());
        }).toList();
        for (var i = 0; i < stale.size(); i++) {
            var note = stale.get(i);
            var text = texts.get(note.noteId());
            var sections = NoteChunker.chunks(titles.get(note.noteId()), text);
            var vectors = embedder.embed(sections.stream().map(NoteChunker.Section::text).toList(), Embedder.Kind.PASSAGE);
            var chunks = new ArrayList<EmbeddedChunk>();
            for (var c = 0; c < sections.size(); c++) {
                chunks.add(new EmbeddedChunk(c, sections.get(c).heading(), vectors[c]));
            }
            platform.storeEmbeddings(job.vaultId(), job.changeSetId(), note.noteId(), embedder.model(), sha256(text), chunks);
            progress(job, "Notizen werden erschlossen", i, stale.size(), 25, 60);
        }
        return true;
    }

    private String mode(ClaimedJob job) {
        try {
            return platform.linkingMode(job.vaultId());
        } catch (RuntimeException unknown) {
            LOG.info("Verlinkungsmodus nicht abrufbar - nur woertliche Nennungen: {}", unknown.getMessage());
            return "LITERAL";
        }
    }

    /** Fortschritt in Zehnteln, damit ein grosser Vault platform-api nicht mit Meldungen flutet. */
    private void progress(ClaimedJob job, String message, int done, int total, int from, int to) {
        var step = Math.max(1, total / 10);
        if (done % step == 0) {
            platform.progress(job.jobId(), message + " (" + (done + 1) + "/" + total + ")", from + (to - from) * done / Math.max(1, total));
        }
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String summary(int links, int notes) {
        if (links == 0) {
            return "Keine neuen Links";
        }
        return links + (links == 1 ? " Link" : " Links") + " in " + notes + (notes == 1 ? " Notiz" : " Notizen") + " gesetzt";
    }
}
