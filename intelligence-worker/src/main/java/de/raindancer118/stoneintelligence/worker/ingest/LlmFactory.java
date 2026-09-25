package de.raindancer118.stoneintelligence.worker.ingest;

import java.util.Map;
import de.raindancer118.stoneai.extract.LlmClient;
import io.github.raindancer118.aigateway.ProviderCapacity;

/** Das Sprachmodell fuer einen KI-Dienst. */
@FunctionalInterface
interface LlmFactory {

    LlmClient forService(ServiceModels.ServiceModel model);

    /** Was die Anbieter des Dienstes noch koennen, soweit aus ihren Antworten bekannt; leer = unbekannt. */
    default Map<String, ProviderCapacity> capacity(ServiceModels.ServiceModel model) {
        return Map.of();
    }

    /** Wie {@link #capacity}, fragt aber Anbieter mit eigener Kontingent-Abfrage nach. */
    default Map<String, ProviderCapacity> refreshCapacity(ServiceModels.ServiceModel model) {
        return capacity(model);
    }
}
