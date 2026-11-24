package de.tstieh.stoneintelligence.platform.search;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;

/** Wie Postgres mit 'simple' und Praefixen, ohne Ranking: Reihenfolge des Einfuegens. */
public final class FakeNoteTextIndex implements NoteTextIndex {

    record Entry(VaultId vaultId, long revision, String text) { }

    public final Map<NoteId, Entry> texts = new LinkedHashMap<>();
    public final Set<Queued> queue = new LinkedHashSet<>();
    private final Map<NoteId, VaultId> known = new HashMap<>();

    /** Wie der Datenbank-Trigger: ein neues Update stellt die Notiz in die Warteschlange. */
    public void enqueue(NoteId noteId, VaultId vaultId) {
        known.put(noteId, vaultId);
        queue.add(new Queued(noteId, vaultId));
    }

    @Override
    public synchronized List<Queued> claim(int limit) {
        var claimed = new ArrayList<Queued>();
        var it = queue.iterator();
        while (it.hasNext() && claimed.size() < limit) {
            claimed.add(it.next());
            it.remove();
        }
        return claimed;
    }

    @Override
    public synchronized void requeue(NoteId noteId) {
        var vault = known.get(noteId);
        if (vault != null) {
            queue.add(new Queued(noteId, vault));
        }
    }

    @Override
    public synchronized void upsert(NoteId noteId, VaultId vaultId, long revision, String text) {
        var current = texts.get(noteId);
        if (current == null || current.revision() <= revision) {
            texts.put(noteId, new Entry(vaultId, revision, text));
        }
    }

    @Override
    public synchronized void remove(NoteId noteId) {
        texts.remove(noteId);
    }

    @Override
    public synchronized List<NoteId> matching(VaultId vaultId, String tsquery, int limit) {
        return texts.entrySet().stream()
            .filter(entry -> entry.getValue().vaultId().equals(vaultId) && matches(entry.getValue().text(), tsquery))
            .map(Map.Entry::getKey).limit(limit).toList();
    }

    @Override
    public synchronized Map<NoteId, String> excerpts(VaultId vaultId, Collection<NoteId> noteIds, String tsquery) {
        var result = new HashMap<NoteId, String>();
        for (var id : noteIds) {
            var entry = texts.get(id);
            if (entry == null || !entry.vaultId().equals(vaultId) || !matches(entry.text(), tsquery)) {
                continue;
            }
            var firstPrefix = prefixes(tsquery).getFirst();
            var marked = Arrays.stream(entry.text().split("\\s+"))
                .map(word -> word.toLowerCase(Locale.ROOT).startsWith(firstPrefix) ? MARK_START + word + MARK_END : word)
                .reduce((a, b) -> a + " " + b).orElse("");
            result.put(id, marked);
        }
        return result;
    }

    private static List<String> prefixes(String tsquery) {
        return Arrays.stream(tsquery.split(" & ")).map(term -> term.replace(":*", "")).toList();
    }

    private static boolean matches(String text, String tsquery) {
        var words = Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")).toList();
        return prefixes(tsquery).stream().allMatch(prefix -> words.stream().anyMatch(word -> word.startsWith(prefix)));
    }
}
