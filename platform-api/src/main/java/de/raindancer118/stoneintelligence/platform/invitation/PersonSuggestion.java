package de.raindancer118.stoneintelligence.platform.invitation;

/** Suchtreffer im Einladungsdialog. Die Adresse ist maskiert - nur zum Wiedererkennen, nicht zum Abgreifen. */
public record PersonSuggestion(String username, String name, String maskedEmail, boolean alreadyMember) {
}
