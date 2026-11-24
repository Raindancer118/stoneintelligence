package de.tstieh.stoneintelligence.platform.sync.relay;

import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Mehrere Instanzen hinter einem Load Balancer: {@code STONEINTELLIGENCE_CLUSTER=true} (ADR 0013, Schritt 7). */
@Configuration
public class ClusterConfig {

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "STONEINTELLIGENCE_CLUSTER", havingValue = "true")
    public PostgresClusterBus postgresClusterBus(DataSource dataSource) {
        return new PostgresClusterBus(dataSource);
    }

    @Bean
    @ConditionalOnProperty(name = "STONEINTELLIGENCE_CLUSTER", havingValue = "false", matchIfMissing = true)
    public ClusterBus singleInstance() {
        return ClusterBus.single();
    }
}
