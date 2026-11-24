package de.tstieh.stoneintelligence.platform;

import de.tstieh.stoneintelligence.domain.id.VaultId;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class ApiExceptionHandlerTest {

    @Test
    void should_answer400_withoutLeakingTheInternalMessage_forMalformedInput() {
        var malformed = catchThrowableOfType(IllegalArgumentException.class, () -> VaultId.of("kein-uuid"));

        var problem = new ApiExceptionHandler().badRequest(malformed);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getDetail()).doesNotContain("kein-uuid");
    }
}
