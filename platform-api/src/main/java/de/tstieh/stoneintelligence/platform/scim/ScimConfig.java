package de.tstieh.stoneintelligence.platform.scim;

import de.tstieh.stoneintelligence.platform.identity.BlockedSubjects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SCIM 2.0 (ADR 0013). Ohne {@code STONEINTELLIGENCE_SCIM_TOKEN} (mindestens 32 Zeichen) ist der
 * Endpunkt zu - jede Anfrage bekommt 401.
 */
@Configuration
public class ScimConfig {

    private static final Logger log = LoggerFactory.getLogger(ScimConfig.class);

    @Bean
    public ScimTokenFilter scimTokenFilter(@Value("${STONEINTELLIGENCE_SCIM_TOKEN:}") String tokens) {
        var filter = new ScimTokenFilter(tokens);
        if (!filter.enabled() && !tokens.isBlank()) {
            log.warn("STONEINTELLIGENCE_SCIM_TOKEN is set but shorter than {} characters - SCIM stays closed",
                ScimTokenFilter.MIN_TOKEN_LENGTH);
        }
        return filter;
    }

    @Bean
    public ScimSettings scimSettings(
            @Value("${STONEINTELLIGENCE_SCIM_SUBJECT_ATTRIBUTE:userName}") String subjectAttribute,
            @Value("${STONEINTELLIGENCE_SCIM_GROUP_KEY:displayName}") String groupKey) {
        return new ScimSettings(ScimSettings.Attribute.parse(subjectAttribute), ScimSettings.Attribute.parse(groupKey));
    }

    @Bean
    public BlockedSubjects blockedSubjects(DirectoryStore directory, ApplicationEventPublisher events) {
        return new BlockedSubjects(directory::blockedSubjects, events);
    }
}
