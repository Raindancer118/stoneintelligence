package de.tstieh.stoneintelligence.platform.identity;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class FakePeopleDirectory implements PeopleDirectory {

    private record Person(String displayName, Instant lastSeen) {
    }

    private final Map<String, Person> people = new HashMap<>();

    @Override
    public synchronized void remember(String subject, String displayName, Instant seenAt) {
        var previous = people.get(subject);
        var lastSeen = previous != null && previous.lastSeen().isAfter(seenAt) ? previous.lastSeen() : seenAt;
        people.put(subject, new Person(displayName, lastSeen));
    }

    @Override
    public synchronized Map<String, String> displayNames(Collection<String> subjects) {
        var names = new HashMap<String, String>();
        subjects.stream().filter(people::containsKey).forEach(subject -> names.put(subject, people.get(subject).displayName()));
        return names;
    }

    @Override
    public synchronized int forgetNotSeenSince(Instant before) {
        var stale = people.entrySet().stream().filter(entry -> entry.getValue().lastSeen().isBefore(before)).map(Map.Entry::getKey).toList();
        stale.forEach(people::remove);
        return stale.size();
    }
}
