package de.tstieh.stoneintelligence.platform.ai;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Die KI darf das nicht (Level, menschliche Notiz, unsicherer Pfad, schon rueckgaengig ...). */
@ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
public class AiWriteRefusedException extends RuntimeException {

    public AiWriteRefusedException(String message) {
        super(message);
    }
}
