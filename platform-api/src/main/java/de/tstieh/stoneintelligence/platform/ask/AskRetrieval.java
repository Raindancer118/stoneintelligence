package de.tstieh.stoneintelligence.platform.ask;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.domain.notelevel.NoteLevel;
import de.tstieh.stoneintelligence.domain.text.NoteChunker;
import de.tstieh.stoneintelligence.platform.ai.AiService;
import de.tstieh.stoneintelligence.platform.ai.AiWriteService;
import de.tstieh.stoneintelligence.platform.ai.NoteEmbeddingRepository;
import de.tstieh.stoneintelligence.platform.search.NoteTextIndex;
import de.tstieh.stoneintelligence.platform.search.SearchQuery;
import de.tstieh.stoneintelligence.platform.vault.Note;
import de.tstieh.stoneintelligence.platform.vault.NoteRepository;
import de.tstieh.stoneintelligence.platform.vault.VaultAccessGuard;

/**
 * Sucht die Textstellen, aus denen "Frag den Vault" antwortet (ADR 0017): Volltext und Embedding-Aehnlichkeit,
 * zusammengefuehrt per Reciprocal Rank Fusion. Gesendet wird nur, was die fragende Person lesen darf, der Dienst
 * verarbeiten darf und - bei externen Diensten - wessen Verfasser eingewilligt hat; erst danach zaehlen Rang und Budget.
 */
public final class AskRetrieval {

    public static final int MAX_PASSAGES = 8;
    public static final int MAX_PER_NOTE = 2;
    /** Geschaetzte Token aller Stellen zusammen - laesst im Kontextfenster Platz fuer Frage, Anweisung und Antwort. */
    public static final int BUDGET_TOKENS = 3_500;
    private static final int CANDIDATES = 40;
    private static final int RRF_K = 60;

    /** @param n die Nummer, unter der die Antwort die Stelle zitiert (ab 1) */
    public record Passage(int n, NoteId noteId, String path, String title, String heading, String text) { }

    /** @param omittedForConsent Notizen mit passenden Stellen, die ein externer Dienst nicht bekommt (Verfasser nicht einverstanden) */
    public record Result(List<Passage> passages, int omittedForConsent) { }

    private final NoteTextIndex texts;
    private final NoteEmbeddingRepository embeddings;
    private final NoteRepository notes;
    private final VaultAccessGuard access;
    private final BiPredicate<AiService, NoteLevel> mayProcess;
    private final double minSimilarity;

    /**
     * @param minSimilarity Kosinus-Untergrenze fuer semantische Treffer; mit multilingual-e5-small liegt Fremdes bei
     *                      etwa 0,75 bis 0,80 (ADR 0012), darunter ist es sicher unverwandt
     */
    public AskRetrieval(NoteTextIndex texts, NoteEmbeddingRepository embeddings, NoteRepository notes, VaultAccessGuard access,
                        BiPredicate<AiService, NoteLevel> mayProcess, double minSimilarity) {
        this.texts = texts;
        this.embeddings = embeddings;
        this.notes = notes;
        this.access = access;
        this.mayProcess = mayProcess;
        this.minSimilarity = minSimilarity;
    }

    /** @param vector Vektor der Frage; {@code null}: nur Volltext */
    public Result retrieve(VaultId vault, String actor, AiService service, Set<String> consents, String question, float[] vector) {
        var scores = new LinkedHashMap<NoteId, Double>();
        var semantic = new HashMap<NoteId, NoteEmbeddingRepository.Similar>();
        SearchQuery.anyOf(question).ifPresent(query -> {
            var rank = 0;
            for (var id : texts.matching(vault, query, CANDIDATES)) {
                scores.merge(id, 1.0 / (RRF_K + ++rank), Double::sum);
            }
        });
        if (vector != null) {
            var rank = 0;
            for (var hit : embeddings.nearest(vault, vector, CANDIDATES)) {
                if (hit.similarity() >= minSimilarity) {
                    scores.merge(hit.noteId(), 1.0 / (RRF_K + ++rank), Double::sum);
                    semantic.put(hit.noteId(), hit);
                }
            }
        }
        if (scores.isEmpty()) {
            return new Result(List.of(), 0);
        }

        var ranked = scores.entrySet().stream().sorted(Map.Entry.<NoteId, Double>comparingByValue().reversed()).map(Map.Entry::getKey).toList();
        var byId = new HashMap<NoteId, Note>();
        notes.findByIds(vault, ranked).forEach(note -> byId.put(note.id(), note));
        var existing = ranked.stream().map(byId::get).filter(note -> note != null && !note.isFile()).toList();
        var readable = new HashSet<>(access.readableNotes(vault, actor, existing).stream().map(Note::id).toList());

        var words = SearchQuery.contentWords(question);
        var passages = new ArrayList<Passage>();
        var omitted = 0;
        var tokens = 0;
        for (var note : existing) {
            if (passages.size() >= MAX_PASSAGES || tokens >= BUDGET_TOKENS) {
                break;
            }
            if (!readable.contains(note.id()) || !mayProcess.test(service, note.level())) {
                continue;
            }
            if (!service.local() && !consents.contains(note.createdBy()) && !AiWriteService.isAgent(note.createdBy())) {
                omitted++;
                continue;
            }
            var body = texts.bodies(vault, List.of(note.id())).get(note.id());
            if (body == null) {
                continue;
            }
            var title = titleOf(note.path());
            for (var section : pick(NoteChunker.chunks(title, body), semantic.get(note.id()), words)) {
                var passage = new Passage(passages.size() + 1, note.id(), note.path(), title, section.heading(), section.body());
                if (passages.size() >= MAX_PASSAGES || tokens + tokens(passage) > BUDGET_TOKENS) {
                    break;
                }
                tokens += tokens(passage);
                passages.add(passage);
            }
        }
        return new Result(List.copyOf(passages), omitted);
    }

    /** Grobe Schaetzung (deutscher Text, etwa drei Zeichen je Token) - lieber zu viel als zu wenig. */
    public static int tokens(Passage passage) {
        return (passage.title().length() + passage.heading().length() + passage.text().length() + 2) / 3;
    }

    /** Der Abschnitt des semantischen Treffers und der mit den meisten Wortern der Frage, in Lesereihenfolge. */
    private static List<NoteChunker.Section> pick(List<NoteChunker.Section> sections, NoteEmbeddingRepository.Similar hit, List<String> words) {
        var chosen = new ArrayList<Integer>();
        if (hit != null) {
            var index = hit.chunk() >= 0 && hit.chunk() < sections.size() && sections.get(hit.chunk()).heading().equals(hit.heading())
                ? hit.chunk() : indexOfHeading(sections, hit.heading());
            if (index >= 0) {
                chosen.add(index);
            }
        }
        var scored = new ArrayList<Integer>();
        for (var i = 0; i < sections.size(); i++) {
            if (!chosen.contains(i) && overlap(sections.get(i), words) > 0) {
                scored.add(i);
            }
        }
        scored.sort(Comparator.comparingInt((Integer i) -> -overlap(sections.get(i), words)));
        for (var i : scored) {
            if (chosen.size() >= MAX_PER_NOTE) {
                break;
            }
            chosen.add(i);
        }
        return chosen.stream().sorted().map(sections::get).toList();
    }

    private static int indexOfHeading(List<NoteChunker.Section> sections, String heading) {
        for (var i = 0; i < sections.size(); i++) {
            if (sections.get(i).heading().equals(heading)) {
                return i;
            }
        }
        return -1;
    }

    private static int overlap(NoteChunker.Section section, List<String> words) {
        var text = section.text().toLowerCase(Locale.ROOT);
        return (int) words.stream().filter(text::contains).count();
    }

    static String titleOf(String path) {
        var name = path.substring(path.lastIndexOf('/') + 1);
        return name.endsWith(".md") ? name.substring(0, name.length() - 3) : name;
    }
}
