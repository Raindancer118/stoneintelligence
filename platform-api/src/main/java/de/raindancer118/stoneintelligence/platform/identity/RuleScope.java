package de.raindancer118.stoneintelligence.platform.identity;

/** Wer eine Themen-ACL ({@link TopicRule}) betrifft - "Jeder" oder ein konkretes Subject. */
public sealed interface RuleScope {

    record Everyone() implements RuleScope {
    }

    record User(String subject) implements RuleScope {
    }

    static RuleScope everyone() {
        return new Everyone();
    }

    static RuleScope user(String subject) {
        return new User(subject);
    }
}
