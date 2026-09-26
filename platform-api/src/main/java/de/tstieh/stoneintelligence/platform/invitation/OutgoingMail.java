package de.tstieh.stoneintelligence.platform.invitation;

public record OutgoingMail(String to, String subject, String text, String html) {
}
