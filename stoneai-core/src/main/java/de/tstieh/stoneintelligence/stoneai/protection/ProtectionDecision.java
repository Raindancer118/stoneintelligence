package de.tstieh.stoneintelligence.stoneai.protection;

/**
 * The answer to "may the AI read this?". Carries the reason so the CLI can tell a user exactly
 * why a document was skipped — a silent skip is indistinguishable from a bug.
 */
public record ProtectionDecision(boolean isProtected, String reason) {

    private static final ProtectionDecision ALLOWED = new ProtectionDecision(false, "");

    public static ProtectionDecision allowed() {
        return ALLOWED;
    }

    public static ProtectionDecision protectedBy(String reason) {
        return new ProtectionDecision(true, reason);
    }
}
