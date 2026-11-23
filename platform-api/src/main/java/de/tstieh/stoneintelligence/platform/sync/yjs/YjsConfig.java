package de.tstieh.stoneintelligence.platform.sync.yjs;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration
public class YjsConfig {

    /** {@code contexts} 0 = so viele wie Prozessoren, hoechstens 8; jeder Context haelt eine eigene Kopie des Bundles im Heap. */
    @Bean(destroyMethod = "close")
    @Lazy
    public YjsBridge yjsBridge(@Value("${stoneintelligence.yjs.contexts:0}") int contexts) {
        return YjsBridge.load(contexts > 0 ? contexts : Math.min(8, Runtime.getRuntime().availableProcessors()));
    }
}
