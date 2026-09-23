package de.raindancer118.stoneintelligence.platform.ai;

import java.util.List;

public record AiRevertReport(int reverted, List<AiRevertConflict> conflicts) {
}
