package de.tstieh.stoneintelligence.platform.sync.relay;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Mehrere "Instanzen" in einem Test: jede Nachricht geht - wie bei NOTIFY - kodiert an alle anderen. */
final class InMemoryClusterHub {

    private final List<Member> members = new CopyOnWriteArrayList<>();

    ClusterBus join() {
        var member = new Member();
        members.add(member);
        return member;
    }

    private final class Member implements ClusterBus {
        private final List<Consumer<ClusterMessage>> listeners = new CopyOnWriteArrayList<>();
        private final String id = "instanz-" + members.size();

        @Override
        public String instanceId() {
            return id;
        }

        @Override
        public void publish(ClusterMessage message) {
            var wire = ClusterMessage.encode(id, message);
            for (var member : members) {
                if (member != this) {
                    var decoded = ClusterMessage.decode(wire).message();
                    member.listeners.forEach(listener -> listener.accept(decoded));
                }
            }
        }

        @Override
        public void subscribe(Consumer<ClusterMessage> listener) {
            listeners.add(listener);
        }
    }
}
