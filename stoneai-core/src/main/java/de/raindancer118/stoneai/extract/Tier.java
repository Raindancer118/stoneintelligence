package de.raindancer118.stoneai.extract;

/**
 * The capability class a call needs, not a concrete model. Which model actually answers is a
 * configuration question ({@code llm.fastChain} and friends), so prompts never hard-code one.
 */
public enum Tier {
    /** Bulk work: one call per chunk. Cheap and fast wins over eloquence. */
    FAST,
    /** Consolidation and final note prose: fewer calls, higher quality. */
    SMART,
    /** Reading text off a page image. */
    VISION
}
