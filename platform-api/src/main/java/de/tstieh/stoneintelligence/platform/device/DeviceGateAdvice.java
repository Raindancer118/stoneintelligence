package de.tstieh.stoneintelligence.platform.device;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 403 mit {@code code} und deutscher Meldung - das Plugin zeigt beides an. */
@RestControllerAdvice
public class DeviceGateAdvice {

    @ExceptionHandler(DeviceGateException.class)
    public ProblemDetail refused(DeviceGateException refused) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, refused.getMessage());
        problem.setProperty("code", refused.code());
        return problem;
    }
}
