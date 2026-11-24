package de.tstieh.stoneintelligence.platform.audit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.platform.identity.AccessResolver;
import de.tstieh.stoneintelligence.platform.identity.Permission;
import de.tstieh.stoneintelligence.platform.vault.Note;
import de.tstieh.stoneintelligence.platform.vault.NoteActivity;
import de.tstieh.stoneintelligence.platform.vault.NoteRepository;
import de.tstieh.stoneintelligence.platform.vault.VaultAccessGuard;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Verlauf und Protokoll" (ADR 0011): wer einen Eintrag angelegt, zuletzt bearbeitet und zuletzt
 * geoeffnet hat, dazu seine Ereignisse - und das Protokoll eines Ordners oder des ganzen Vaults.
 *
 * <p>Jede Person sieht nur, was sie sehen darf: Ereignisse an Stellen, die sie lesen kann;
 * Freigabe-Ereignisse nur, wo sie verwaltet (sie verraten, wer Zugriff hat); Ereignisse ohne Ort
 * (Mitglieder, Rollen, Gruppen) nur mit Verwalten-Recht im Vault.
 */
@RestController
public class HistoryController {

    static final int MAX_LIMIT = 500;

    private final VaultAccessGuard access;
    private final NoteRepository notes;
    private final AuditReader audit;

    public HistoryController(VaultAccessGuard access, NoteRepository notes, AuditReader audit) {
        this.access = access;
        this.notes = notes;
        this.audit = audit;
    }

    @GetMapping("/api/v1/vaults/{vaultId}/notes/{noteId}/history")
    public NoteHistory noteHistory(@PathVariable String vaultId, @PathVariable String noteId, Authentication auth) {
        var vId = VaultId.of(vaultId);
        var nId = NoteId.of(noteId);
        access.requireMember(vId, auth.getName());
        var note = notes.findById(vId, nId);
        note.ifPresent(existing -> access.require(vId, auth.getName(), Permission.READ, existing.path()));
        var all = audit.listForNote(vId, nId);
        // Geloescht: der letzte bekannte Ort aus dem Protokoll entscheidet, wer den Verlauf sieht.
        var place = note.map(Note::path).orElseGet(() -> lastKnownPath(all));
        var visible = visibility(vId, auth.getName());
        var events = all.stream()
            .map(event -> visible.apply(event, place))
            .flatMap(Optional::stream)
            .toList();
        return new NoteHistory(note.isPresent() ? notes.activity(vId, nId).orElse(null) : null, events);
    }

    /** {@code path} = Ordner (leer = ganzer Vault); neueste Ereignisse zuerst. */
    @GetMapping("/api/v1/vaults/{vaultId}/audit")
    public List<EventResponse> vaultLog(@PathVariable String vaultId, @RequestParam(defaultValue = "") String path,
                                        @RequestParam(defaultValue = "100") int limit, Authentication auth) {
        var vId = VaultId.of(vaultId);
        access.requireMember(vId, auth.getName());
        var folder = AccessResolver.normalize(path);
        var visible = visibility(vId, auth.getName());
        var currentPaths = new HashMap<NoteId, Optional<String>>();
        var result = new ArrayList<EventResponse>();
        // Etwas mehr lesen als verlangt: ein Teil faellt beim Filtern weg.
        for (var event : audit.listRecent(vId, Math.min(Math.max(limit, 1), MAX_LIMIT) * 3)) {
            var fallback = event.noteId() == null ? null : currentPaths
                .computeIfAbsent(event.noteId(), id -> notes.findById(vId, id).map(Note::path)).orElse(null);
            var shown = visible.apply(event, fallback);
            if (shown.isPresent() && (folder.isEmpty() || shown.get().paths().stream().anyMatch(p -> isAtOrBelow(p, folder)))) {
                result.add(shown.get());
                if (result.size() >= Math.min(Math.max(limit, 1), MAX_LIMIT)) {
                    break;
                }
            }
        }
        return result;
    }

    private java.util.function.BiFunction<AuditEvent, String, Optional<EventResponse>> visibility(VaultId vaultId, String actor) {
        var checker = access.accessChecker(vaultId, actor);
        var vaultManager = access.membership(vaultId, actor).vaultPermissions().contains(Permission.MANAGE);
        return (event, fallbackPath) -> {
            var paths = pathsOf(event);
            if (paths.isEmpty() && fallbackPath != null) {
                paths = List.of(fallbackPath);
            }
            if (paths.isEmpty()) {
                return vaultManager ? Optional.of(EventResponse.from(event, null, paths)) : Optional.empty();
            }
            var accessEvent = event.action().startsWith("ACCESS_");
            var isFolder = "folder".equals(event.payload().get("target"));
            for (var path : paths) {
                var at = checker.apply(isFolder ? path + "/" : path);
                if (!at.allows(Permission.READ) && !at.allows(Permission.MANAGE)) {
                    return Optional.empty();
                }
                if (accessEvent && !at.allows(Permission.MANAGE)) {
                    return Optional.empty();
                }
            }
            return Optional.of(EventResponse.from(event, paths.getLast(), paths));
        };
    }

    private static String lastKnownPath(List<AuditEvent> events) {
        String last = null;
        for (var event : events) {
            var paths = pathsOf(event);
            if (!paths.isEmpty() && !event.action().startsWith("ACCESS_")) {
                last = paths.getLast();
            }
        }
        return last;
    }

    private static List<String> pathsOf(AuditEvent event) {
        return Stream.of("path", "from", "to").map(event.payload()::get)
            .filter(String.class::isInstance).map(String.class::cast).distinct().toList();
    }

    private static boolean isAtOrBelow(String path, String folder) {
        return path.equals(folder) || path.startsWith(folder + "/");
    }

    /** {@code activity == null}: der Eintrag ist geloescht, es bleibt sein Protokoll. */
    public record NoteHistory(NoteActivity activity, List<EventResponse> events) {
    }

    /** {@code path}: wo es geschah (bei Umbenennungen der neue Pfad), {@code null} bei Ereignissen ohne Ort. */
    public record EventResponse(String actor, String action, Map<String, Object> payload, Instant occurredAt,
                                String noteId, String path, List<String> paths) {
        static EventResponse from(AuditEvent event, String path, List<String> paths) {
            return new EventResponse(event.actor(), event.action(), event.payload(), event.occurredAt(),
                event.noteId() == null ? null : event.noteId().value().toString(), path, paths);
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper()
        .findAndRegisterModules().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final int EXPORT_PAGE = 1000;

    /**
     * Das Protokoll als Datei, etwa fuer ein SIEM: {@code format} csv oder jsonl, {@code from}/{@code to} als
     * ISO-Zeitpunkt (leer = alles). Nur fuer Verwaltende; sichtbar ist, was auch das Protokoll im Dashboard zeigt.
     */
    @GetMapping("/api/v1/vaults/{vaultId}/audit/export")
    public void exportLog(@PathVariable String vaultId, @RequestParam(defaultValue = "csv") String format,
                          @RequestParam(defaultValue = "") String from, @RequestParam(defaultValue = "") String to,
                          Authentication auth, jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        var vId = VaultId.of(vaultId);
        access.require(vId, auth.getName(), Permission.MANAGE);
        var csv = switch (format) {
            case "csv" -> true;
            case "jsonl" -> false;
            default -> throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "format must be csv or jsonl");
        };
        var start = instant(from, Instant.EPOCH);
        var end = instant(to, Instant.now().plusSeconds(1));
        var visible = visibility(vId, auth.getName());

        response.setCharacterEncoding("UTF-8");
        response.setContentType(csv ? "text/csv" : "application/x-ndjson");
        response.setHeader("Content-Disposition", "attachment; filename=\"stoneintelligence-audit-" + vaultId + (csv ? ".csv\"" : ".jsonl\""));
        var out = response.getWriter();
        if (csv) {
            out.write(csvLine(List.of("occurred_at", "actor", "action", "path", "note_id", "details")));
        }
        var paths = new HashMap<NoteId, Optional<String>>();
        AuditEvent after = null;
        while (true) {
            var page = audit.listBetween(vId, start, end, after, EXPORT_PAGE);
            for (var event : page) {
                var fallback = event.noteId() == null ? null
                    : paths.computeIfAbsent(event.noteId(), id -> notes.findById(vId, id).map(Note::path)).orElse(null);
                var shown = visible.apply(event, fallback);
                if (shown.isEmpty()) {
                    continue;
                }
                var path = shown.get().paths().isEmpty() ? "" : shown.get().paths().getLast();
                var noteId = event.noteId() == null ? "" : event.noteId().value().toString();
                if (csv) {
                    out.write(csvLine(List.of(event.occurredAt().toString(), event.actor(), event.action(), path, noteId,
                        JSON.writeValueAsString(event.payload()))));
                } else {
                    var line = new java.util.LinkedHashMap<String, Object>();
                    line.put("occurredAt", event.occurredAt().toString());
                    line.put("actor", event.actor());
                    line.put("action", event.action());
                    line.put("path", path);
                    line.put("noteId", noteId);
                    line.put("details", event.payload());
                    out.write(JSON.writeValueAsString(line) + "\n");
                }
            }
            if (page.size() < EXPORT_PAGE) {
                break;
            }
            after = page.getLast();
        }
        out.flush();
    }

    private static Instant instant(String value, Instant fallback) {
        if (value.isBlank()) {
            return fallback;
        }
        try {
            return Instant.parse(value.strip());
        } catch (java.time.format.DateTimeParseException e) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "from/to must be ISO instants like 2026-01-31T00:00:00Z");
        }
    }

    /** Alles in Anfuehrungszeichen; was mit = + - @ beginnt, bekommt ein ' davor (keine Formel in Excel). */
    static String csvLine(List<String> cells) {
        var line = new StringBuilder();
        for (var cell : cells) {
            if (!line.isEmpty()) {
                line.append(',');
            }
            var value = cell == null ? "" : cell;
            if (!value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0) {
                value = "'" + value;
            }
            line.append('"').append(value.replace("\"", "\"\"")).append('"');
        }
        return line.append("\r\n").toString();
    }
}
