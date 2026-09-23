package de.raindancer118.stoneintelligence.platform.ai;

import java.util.List;
import java.util.UUID;
import de.raindancer118.stoneintelligence.domain.id.NoteId;
import de.raindancer118.stoneintelligence.domain.id.VaultId;
import de.raindancer118.stoneintelligence.domain.notelevel.NoteLevel;
import de.raindancer118.stoneintelligence.platform.identity.Permission;
import de.raindancer118.stoneintelligence.platform.vault.NoteNotFoundException;
import de.raindancer118.stoneintelligence.platform.vault.NoteRepository;
import de.raindancer118.stoneintelligence.platform.vault.VaultAccessGuard;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Der Schreibweg des KI-Workers (ADR 0008), nur mit Worker-Token erreichbar. Die KI handelt
 * hoechstens mit den Rechten der Person, die die Verarbeitung ausgeloest hat ({@code requestedBy}
 * des Change-Sets), und nur auf den Levels ihres Dienstes.
 */
@RestController
@RequestMapping("/internal/ai")
public class AiInternalController {

    static final int MAX_TEXT_LENGTH = 1_000_000;

    private final AiWriteService ai;
    private final AiServiceDirectory services;
    private final VaultAccessGuard access;
    private final NoteRepository notes;

    public AiInternalController(@Lazy AiWriteService ai, AiServiceDirectory services, VaultAccessGuard access, NoteRepository notes) {
        this.ai = ai;
        this.services = services;
        this.access = access;
        this.notes = notes;
    }

    @GetMapping("/services")
    public List<AiServiceResponse> services() {
        return services.all().stream().map(AiServiceResponse::from).toList();
    }

    @PostMapping("/vaults/{vaultId}/change-sets")
    public ChangeSetCreated startChangeSet(@PathVariable String vaultId, @RequestBody StartChangeSetRequest request) {
        var vId = VaultId.of(vaultId);
        if (request.requestedBy() == null || request.label() == null || request.label().isBlank() || request.label().length() > 300) {
            throw new AiWriteRefusedException("requestedBy und label (1..300 Zeichen) sind Pflicht");
        }
        access.require(vId, request.requestedBy(), Permission.CREATE);
        var service = services.find(String.valueOf(request.service()))
            .orElseThrow(() -> new AiWriteRefusedException("KI-Dienst " + request.service() + " ist nicht eingerichtet"));
        return new ChangeSetCreated(ai.startChangeSet(vId, service, request.requestedBy(), request.label().strip()).id());
    }

    @PostMapping("/vaults/{vaultId}/change-sets/{changeSetId}/notes")
    public NoteRef createNote(@PathVariable String vaultId, @PathVariable UUID changeSetId, @RequestBody CreateNoteRequest request) {
        var vId = VaultId.of(vaultId);
        var changeSet = changeSet(vId, changeSetId);
        access.require(vId, changeSet.requestedBy(), Permission.CREATE, String.valueOf(request.path()));
        if (request.level() == null) {
            throw new AiWriteRefusedException("level ist Pflicht");
        }
        var written = ai.createNote(vId, changeSetId, request.path(), text(request.text()), NoteLevel.of(request.level()));
        return new NoteRef(written.noteId().value().toString(), written.path());
    }

    @PutMapping("/vaults/{vaultId}/change-sets/{changeSetId}/notes/{noteId}")
    public NoteRef updateNote(@PathVariable String vaultId, @PathVariable UUID changeSetId, @PathVariable String noteId,
                                  @RequestBody UpdateNoteRequest request) {
        var vId = VaultId.of(vaultId);
        var nId = NoteId.of(noteId);
        var changeSet = changeSet(vId, changeSetId);
        var note = notes.findById(vId, nId).orElseThrow(() -> new NoteNotFoundException(vId, nId));
        access.require(vId, changeSet.requestedBy(), Permission.WRITE, note.path());
        ai.updateNote(vId, changeSetId, nId, text(request.text()));
        return new NoteRef(noteId, note.path());
    }

    @GetMapping("/vaults/{vaultId}/change-sets/{changeSetId}/notes/{noteId}")
    public NoteText readNote(@PathVariable String vaultId, @PathVariable UUID changeSetId, @PathVariable String noteId) {
        var vId = VaultId.of(vaultId);
        var nId = NoteId.of(noteId);
        var changeSet = changeSet(vId, changeSetId);
        var note = notes.findById(vId, nId).orElseThrow(() -> new NoteNotFoundException(vId, nId));
        access.require(vId, changeSet.requestedBy(), Permission.READ, note.path());
        var service = services.find(changeSet.service())
            .orElseThrow(() -> new AiWriteRefusedException("KI-Dienst " + changeSet.service() + " ist nicht (mehr) eingerichtet"));
        return new NoteText(noteId, note.path(), ai.readText(vId, nId, service));
    }

    private AiChangeSet changeSet(VaultId vaultId, UUID changeSetId) {
        return ai.changeSet(vaultId, changeSetId).orElseThrow(() -> new AiWriteRefusedException("KI-Änderung nicht gefunden"));
    }

    private static String text(String text) {
        if (text == null || text.length() > MAX_TEXT_LENGTH) {
            throw new AiWriteRefusedException("text fehlt oder ist länger als " + MAX_TEXT_LENGTH + " Zeichen");
        }
        return text;
    }

    public record StartChangeSetRequest(String service, String requestedBy, String label) { }
    public record ChangeSetCreated(UUID id) { }
    public record CreateNoteRequest(String path, String text, Integer level) { }
    public record UpdateNoteRequest(String text) { }
    public record NoteRef(String noteId, String path) { }
    public record NoteText(String noteId, String path, String text) { }
}
