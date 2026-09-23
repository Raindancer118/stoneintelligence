package de.raindancer118.stoneintelligence.worker.ingest;

import de.raindancer118.stoneai.extract.LlmClient;

/** Das Sprachmodell fuer einen KI-Dienst. */
@FunctionalInterface
interface LlmFactory {

    LlmClient forService(ServiceModels.ServiceModel model);
}
