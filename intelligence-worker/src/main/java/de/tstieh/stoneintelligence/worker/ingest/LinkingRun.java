package de.tstieh.stoneintelligence.worker.ingest;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Stream;
import de.tstieh.stoneintelligence.stoneai.vault.IndexedNote;
import de.tstieh.stoneintelligence.worker.link.MentionFinder;
import de.tstieh.stoneintelligence.worker.platform.ClaimedJob;
import de.tstieh.stoneintelligence.worker.platform.PlatformApi;
import de.tstieh.stoneintelligence.worker.platform.ProposedLink;

/**
 * Ein Verlinkungslauf (ADR 0012), Stufe 1: nennt eine Notiz Titel oder Alias einer anderen
 * woertlich, wird die Stelle verlinkt. Braucht kein Sprachmodell - nichts verlaesst den Server.
 * Welche Notizen der Lauf sieht (lesbar fuer die Person dahinter, Level erlaubt) und ob in Notizen
 * von Menschen verlinkt werden darf, entscheidet platform-api; der Server setzt auch die Links selbst.
 */
final class LinkingRun {

    private final PlatformApi platform;

    LinkingRun(PlatformApi platform) {
        this.platform = platform;
    }

    void run(ClaimedJob job) {
        platform.progress(job.jobId(), "Notizen werden gelesen", 5);
        var notes = platform.notes(job.vaultId(), job.changeSetId()).stream()
            .filter(note -> !note.isFile() && note.path().toLowerCase(java.util.Locale.ROOT).endsWith(".md"))
            .toList();
        var texts = new LinkedHashMap<String, String>();
        var names = new LinkedHashMap<String, List<String>>();
        var step = Math.max(1, notes.size() / 10);
        for (var i = 0; i < notes.size(); i++) {
            var note = notes.get(i);
            var text = platform.read(job.vaultId(), job.changeSetId(), note.noteId());
            texts.put(note.noteId(), text);
            var indexed = IndexedNote.fromContent(Path.of(note.path()), text);
            names.put(note.noteId(), Stream.concat(Stream.of(indexed.title()), indexed.aliases().stream()).distinct().toList());
            if (i % step == 0) {
                platform.progress(job.jobId(), "Notizen werden gelesen (" + (i + 1) + "/" + notes.size() + ")", 5 + 35 * i / notes.size());
            }
        }

        var finder = new MentionFinder(names);
        var links = 0;
        var changed = 0;
        for (var i = 0; i < notes.size(); i++) {
            var note = notes.get(i);
            var mentions = finder.find(note.noteId(), texts.get(note.noteId()));
            if (!mentions.isEmpty()) {
                var applied = platform.link(job.vaultId(), job.changeSetId(), note.noteId(),
                    mentions.stream().map(mention -> new ProposedLink(mention.targetNoteId(), mention.anchor(), false)).toList());
                links += applied;
                changed += applied > 0 ? 1 : 0;
            }
            if (i % step == 0) {
                platform.progress(job.jobId(), "Verlinke (" + (i + 1) + "/" + notes.size() + ")", 40 + 55 * i / notes.size());
            }
        }
        platform.progress(job.jobId(), summary(links, changed), 100);
        platform.complete(job.jobId());
    }

    private static String summary(int links, int notes) {
        if (links == 0) {
            return "Keine neuen Links";
        }
        return links + (links == 1 ? " Link" : " Links") + " in " + notes + (notes == 1 ? " Notiz" : " Notizen") + " gesetzt";
    }
}
