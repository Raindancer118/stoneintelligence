package de.tstieh.stoneintelligence.platform.sync.ticket;

import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TicketConfig {

    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }

    @Bean
    public TicketService ticketService(
        Clock systemClock,
        TicketStore ticketStore,
        @Value("${stoneintelligence.sync.ticket-ttl-seconds:30}") long ticketTtlSeconds
    ) {
        return new TicketService(systemClock, Duration.ofSeconds(ticketTtlSeconds), ticketStore);
    }
}
