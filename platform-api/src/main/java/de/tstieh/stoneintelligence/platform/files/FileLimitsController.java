package de.tstieh.stoneintelligence.platform.files;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Welche Grenzen gelten (ADR 0009 Punkt 6) - das Plugin prueft vor dem Hochladen, statt 200 MB umsonst zu senden. */
@RestController
public class FileLimitsController {

    private final FileService files;

    public FileLimitsController(FileService files) {
        this.files = files;
    }

    @GetMapping("/api/v1/files/limits")
    public FileLimits limits() {
        return files.limits();
    }
}
