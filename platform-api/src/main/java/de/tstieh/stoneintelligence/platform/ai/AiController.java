package de.tstieh.stoneintelligence.platform.ai;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.platform.identity.Permission;
import de.tstieh.stoneintelligence.platform.vault.VaultAccessGuard;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Was die KI in einem Vault getan hat - ansehen und rueckgaengig machen (ADR 0008). */
@RestController
public class AiController {

    private static final int LIST_LIMIT = 100;

    private final AiWriteService ai;
    private final AiServiceDirectory services;
    private final VaultAccessGuard access;
    private final AiJobService jobs;
    private final AiCapacityBoard capacity;
    private final VaultFileJobs fileJobs;

    public AiController(@Lazy AiWriteService ai, AiServiceDirectory services, VaultAccessGuard access, AiJobService jobs,
                        AiCapacityBoard capacity, VaultFileJobs fileJobs) {
        this.fileJobs = fileJobs;
        this.jobs = jobs;
        this.capacity = capacity;
        this.ai = ai;
        this.services = services;
        this.access = access;
    }

    /** Welche KI-Dienste angebunden sind und welche Levels sie verarbeiten duerfen. */
    @GetMapping("/api/v1/ai/services")
    public List<AiServiceResponse> services() {
        return services.all().stream().map(AiServiceResponse::from).toList();
    }

    /** Wie viel Kontingent der Dienst noch hat und, wenn keins, ab wann wieder - laut letzter Worker-Meldung. */
    @GetMapping("/api/v1/ai/services/{serviceId}/capacity")
    public AiCapacityBoard.ServiceCapacity capacity(@PathVariable String serviceId) {
        return capacity.of(serviceId);
    }

    @GetMapping("/api/v1/vaults/{vaultId}/ai/change-sets")
    public List<ChangeSetResponse> list(@PathVariable String vaultId, Authentication auth) {
        var vId = VaultId.of(vaultId);
        access.require(vId, auth.getName(), Permission.READ);
        return ai.changeSets(vId, LIST_LIMIT).stream().map(ChangeSetResponse::from).toList();
    }

    @GetMapping("/api/v1/vaults/{vaultId}/ai/change-sets/{changeSetId}")
    public ChangeSetDetail detail(@PathVariable String vaultId, @PathVariable UUID changeSetId, Authentication auth) {
        var vId = VaultId.of(vaultId);
        access.require(vId, auth.getName(), Permission.READ);
        var changeSet = ai.changeSet(vId, changeSetId).orElseThrow(() -> new AiWriteRefusedException("KI-Änderung nicht gefunden"));
        var changes = ai.changes(vId, changeSetId);
        var readable = access.readablePaths(vId, auth.getName(), changes.stream().map(AiChange::path).toList(), UnaryOperator.identity());
        return new ChangeSetDetail(ChangeSetResponse.from(changeSet), changes.stream()
            .filter(change -> readable.contains(change.path()))
            .map(change -> new ChangeResponse(change.noteId().value().toString(), change.path(), change.kind().name(), change.at()))
            .toList());
    }

    @PostMapping("/api/v1/vaults/{vaultId}/ai/change-sets/{changeSetId}/revert")
    public AiRevertReport revert(@PathVariable String vaultId, @PathVariable UUID changeSetId, Authentication auth) {
        var vId = VaultId.of(vaultId);
        access.require(vId, auth.getName(), Permission.WRITE);
        access.require(vId, auth.getName(), Permission.DELETE);
        var report = ai.revert(vId, changeSetId, auth.getName());
        var readable = access.readablePaths(vId, auth.getName(),
            report.conflicts().stream().map(AiRevertConflict::path).toList(), UnaryOperator.identity());
        return new AiRevertReport(report.reverted(),
            report.conflicts().stream().filter(conflict -> readable.contains(conflict.path())).toList());
    }

    /** Dokumente hochladen: je Datei ein Job fuer den gewaehlten Dienst, Level = Level des Dokuments. */
    @PostMapping(path = "/api/v1/vaults/{vaultId}/ai/jobs", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<JobResponse> upload(@PathVariable String vaultId,
                                    @org.springframework.web.bind.annotation.RequestParam String service,
                                    @org.springframework.web.bind.annotation.RequestParam(defaultValue = "1") int level,
                                    @org.springframework.web.bind.annotation.RequestParam("files")
                                    List<org.springframework.web.multipart.MultipartFile> files,
                                    Authentication auth) throws java.io.IOException {
        var vId = VaultId.of(vaultId);
        access.require(vId, auth.getName(), Permission.CREATE);
        var uploads = new java.util.ArrayList<AiJobService.Upload>();
        for (var file : files) {
            uploads.add(new AiJobService.Upload(file.getOriginalFilename(), file.getContentType(), file.getBytes()));
        }
        return jobs.upload(vId, auth.getName(), service, level, uploads).stream().map(JobResponse::from).toList();
    }

    /** Dateien, die schon im Vault liegen, einlesen lassen (Obsidian: "Mit KI einlesen"). */
    @PostMapping(path = "/api/v1/vaults/{vaultId}/ai/jobs/from-files", consumes = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
    public List<JobResponse> fromFiles(@PathVariable String vaultId,
                                       @org.springframework.web.bind.annotation.RequestBody FromFilesRequest request,
                                       Authentication auth) {
        var vId = VaultId.of(vaultId);
        var ids = request.fileIds() == null ? List.<de.tstieh.stoneintelligence.domain.id.NoteId>of()
            : request.fileIds().stream().map(de.tstieh.stoneintelligence.domain.id.NoteId::of).toList();
        return fileJobs.start(vId, auth.getName(), request.service(), ids).stream().map(JobResponse::from).toList();
    }

    public record FromFilesRequest(String service, List<String> fileIds) {
    }

    @GetMapping("/api/v1/vaults/{vaultId}/ai/jobs")
    public List<JobResponse> jobs(@PathVariable String vaultId, Authentication auth) {
        var vId = VaultId.of(vaultId);
        access.require(vId, auth.getName(), Permission.READ);
        return jobs.list(vId, LIST_LIMIT).stream().map(JobResponse::from).toList();
    }

    @PostMapping("/api/v1/vaults/{vaultId}/ai/jobs/{jobId}/cancel")
    public JobResponse cancel(@PathVariable String vaultId, @PathVariable UUID jobId, Authentication auth) {
        var vId = VaultId.of(vaultId);
        access.require(vId, auth.getName(), Permission.CREATE);
        if (!jobs.cancel(vId, jobId, auth.getName())) {
            throw new AiWriteRefusedException("Nur wartende oder laufende Verarbeitungen lassen sich abbrechen");
        }
        return jobs.list(vId, LIST_LIMIT).stream().filter(job -> job.id().equals(jobId)).findFirst().map(JobResponse::from)
            .orElseThrow(() -> new AiWriteRefusedException("Job nicht gefunden"));
    }

    /** Die Texte sind fuer Menschen geschrieben und verraten nichts Internes - direkt anzeigen lassen. */
    @org.springframework.web.bind.annotation.ExceptionHandler(AiWriteRefusedException.class)
    public org.springframework.http.ProblemDetail refused(AiWriteRefusedException refused) {
        return org.springframework.http.ProblemDetail.forStatusAndDetail(
            org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT, refused.getMessage());
    }

    public record JobResponse(UUID id, String service, String requestedBy, String fileName, long size, int level, String status,
                              String progress, Integer percent, String error, UUID changeSetId, Instant createdAt,
                              Instant finishedAt, Instant availableAt, boolean waitingForCapacity, String kind) {
        static JobResponse from(AiJob job) {
            return new JobResponse(job.id(), job.service(), job.requestedBy(), job.fileName(), job.size(), job.level(),
                job.status().name(), job.progress(), job.percent(), job.error(), job.changeSetId(), job.createdAt(), job.finishedAt(),
                job.status() == AiJob.Status.PENDING ? job.availableAt() : null, job.waitingForCapacity(), job.kind().name());
        }
    }

    public record ChangeSetResponse(UUID id, String service, String agent, String requestedBy, String label,
                                    Instant createdAt, Instant revertedAt) {
        static ChangeSetResponse from(AiChangeSet set) {
            return new ChangeSetResponse(set.id(), set.service(), set.agent(), set.requestedBy(), set.label(), set.createdAt(),
                set.revertedAt());
        }
    }

    public record ChangeResponse(String noteId, String path, String kind, Instant at) { }
    public record ChangeSetDetail(ChangeSetResponse changeSet, List<ChangeResponse> changes) { }
}
