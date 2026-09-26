package de.tstieh.stoneintelligence.platform;

import org.testcontainers.utility.DockerImageName;

/** Dasselbe Postgres wie im Betrieb (dorn, docker-compose.yml): mit pgvector fuer die Embeddings (ADR 0012). */
public final class TestImages {

    public static final DockerImageName POSTGRES =
        DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres");

    private TestImages() {
    }
}
