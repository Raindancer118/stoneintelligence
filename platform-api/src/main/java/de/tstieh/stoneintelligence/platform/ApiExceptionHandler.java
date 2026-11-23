package de.tstieh.stoneintelligence.platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Ungueltige Ids, Level, Cursor usw. sind Clientfehler (400), kein 500; Controller-eigene Handler gehen vor. */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class ApiExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail badRequest(IllegalArgumentException invalid) {
        LOG.debug("Rejected request: {}", invalid.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid request parameter");
    }
}
