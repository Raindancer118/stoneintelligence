package de.tstieh.stoneintelligence.platform.vault.feed;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.platform.identity.Membership;
import de.tstieh.stoneintelligence.platform.vault.EntryListing;
import de.tstieh.stoneintelligence.platform.vault.Note;
import de.tstieh.stoneintelligence.platform.vault.NoteController;
import de.tstieh.stoneintelligence.platform.vault.NoteKind;
import de.tstieh.stoneintelligence.platform.vault.NoteRepository;
import de.tstieh.stoneintelligence.platform.vault.SyncScope;
import de.tstieh.stoneintelligence.platform.vault.VaultAccessGuard;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Aenderungs-Feed (ADR 0013): statt alle 30 s die ganze Liste zu holen, fragt ein Geraet, was sich seit
 * seinem Cursor geaendert hat. Ohne {@code since} gibt es nur einen Cursor - den holt ein Geraet, BEVOR
 * es einmal die volle Liste laedt, damit dazwischen nichts verloren geht.
 *
 * <p>{@code removed}: geloescht oder fuer diese Person nicht mehr lesbar. {@code relist}: Ordner
 * ({@code ""} = alles), in denen sich ihre Sicht durch Freigaben oder Mitgliedschaften geaendert haben
 * kann - dort holt das Geraet die Liste neu. Ereignisse anderer Personen und fremder Gruppen kommen gar
 * nicht erst an, damit nicht jede Freigabe im Konzern alle Geraete neu laden laesst.
 */
@RestController
public class ChangeController {

    static final int MAX_LIMIT = 2000;

    private final ChangeFeed feed;
    private final NoteRepository notes;
    private final VaultAccessGuard access;
    private final EntryListing listing;

    public ChangeController(ChangeFeed feed, NoteRepository notes, VaultAccessGuard access, EntryListing listing) {
        this.feed = feed;
        this.notes = notes;
        this.access = access;
        this.listing = listing;
    }

    @GetMapping("/api/v1/vaults/{vaultId}/changes")
    public Changes changes(@PathVariable String vaultId, @RequestParam(required = false) String since,
                           @RequestParam(defaultValue = "500") int limit, @RequestParam(defaultValue = "note") String kinds,
                           @RequestParam(required = false) List<String> scope, Authentication auth) {
        var vId = VaultId.of(vaultId);
        var actor = auth.getName();
        access.requireMember(vId, actor);
        if (since == null || since.isBlank()) {
            return Changes.start(FeedCursor.at(feed.now()));
        }
        FeedCursor cursor;
        try {
            cursor = FeedCursor.parse(since);
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid cursor");
        }
        var wanted = parseKinds(kinds);
        SyncScope areas;
        try {
            areas = scope == null ? SyncScope.wholeVault() : SyncScope.of(scope);
        } catch (IllegalArgumentException tooMany) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, tooMany.getMessage());
        }
        var batch = feed.read(vId, cursor, Math.clamp(limit, 1, MAX_LIMIT));
        var who = access.membership(vId, actor);

        var ids = new LinkedHashSet<>(batch.changed());
        var relist = new TreeSet<String>();
        for (var event : batch.accessEvents()) {
            if (!concerns(event, who)) {
                continue;
            }
            if (event.noteId() != null) {
                ids.add(event.noteId());
            } else if (areas.touchesFolder(event.folderPath()) || event.folderPath().isEmpty()) {
                relist.add(event.folderPath());
            }
        }

        var all = notes.findByIds(vId, ids);
        // Ausserhalb der Bereiche: fuer dieses Geraet "weg" - es heftet die Notiz an, falls es sie hat.
        var readable = access.readableNotes(vId, actor, all.stream()
            .filter(note -> wanted.contains(note.kind()) && areas.covers(note.path())).toList());
        var keep = new java.util.HashSet<NoteId>(readable.stream().map(Note::id).toList());
        // Eintraege einer nicht angefragten Art (aeltere Clients ohne Dateien) sind weder da noch weg.
        all.stream().filter(note -> !wanted.contains(note.kind())).forEach(note -> keep.add(note.id()));
        var removed = ids.stream().filter(id -> !keep.contains(id)).map(id -> id.value().toString()).toList();
        var asFolder = (java.util.function.UnaryOperator<String>) path -> path + "/";
        return new Changes(batch.next().format(), batch.more(), listing.listed(vId, actor, readable), removed,
            new Folders(access.readablePaths(vId, actor, batch.foldersChanged().stream().filter(areas::touchesFolder).toList(), asFolder),
                access.readablePaths(vId, actor, batch.foldersRemoved().stream().filter(areas::touchesFolder).toList(), asFolder)),
            outermost(relist));
    }

    private static boolean concerns(ChangeFeed.AccessEvent event, Membership who) {
        return switch (event.scopeType()) {
            case "EVERYONE" -> true;
            case "USER" -> who.subject().equals(event.scopeSubject());
            case "GROUP" -> who.groupIds().stream().anyMatch(group -> group.toString().equals(event.scopeSubject()));
            default -> false;
        };
    }

    /** Nur die aeussersten Ordner - was darunter liegt, wird mit ihnen ohnehin neu geholt. */
    private static List<String> outermost(TreeSet<String> folders) {
        if (folders.contains("")) {
            return List.of("");
        }
        var result = new ArrayList<String>();
        for (var folder : folders) {
            if (result.stream().noneMatch(outer -> folder.startsWith(outer + "/"))) {
                result.add(folder);
            }
        }
        return result;
    }

    private static Set<NoteKind> parseKinds(String kinds) {
        var parsed = EnumSet.noneOf(NoteKind.class);
        for (var kind : kinds.split(",")) {
            switch (kind.strip().toLowerCase(Locale.ROOT)) {
                case "note" -> parsed.add(NoteKind.NOTE);
                case "file" -> parsed.add(NoteKind.FILE);
                default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown kind: " + kind);
            }
        }
        return parsed;
    }

    public record Folders(List<String> changed, List<String> removed) { }

    /** {@code more}: gleich noch einmal mit {@code cursor} fragen, der Bereich ist noch nicht ganz geliefert. */
    public record Changes(String cursor, boolean more, List<NoteController.ListedNoteResponse> entries, List<String> removed,
                          Folders folders, List<String> relist) {
        static Changes start(FeedCursor cursor) {
            return new Changes(cursor.format(), false, List.of(), List.of(), new Folders(List.of(), List.of()), List.of());
        }
    }
}
