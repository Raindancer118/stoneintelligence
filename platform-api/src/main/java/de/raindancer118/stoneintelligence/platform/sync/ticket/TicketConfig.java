package de.raindancer118.stoneintelligence.platform.sync.ticket;

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
        @Value("${stoneintelligence.sync.ticket-ttl-seconds:30}") long ticketTtlSeconds
    ) {
        return new TicketService(systemClock, Duration.ofSeconds(ticketTtlSeconds));
    }
}
