package de.tstieh.stoneintelligence.platform.scim;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import de.tstieh.stoneintelligence.platform.identity.FakeAuthorizationRepository;

/** Haelt {@link FakeAuthorizationRepository} auf dem Stand, den in Postgres die Sicht effective_group_members liefert. */
public class FakeDirectoryStore implements DirectoryStore {

    private record StoredAccount(Account account, Instant deletedAt) {
        boolean deleted() {
            return deletedAt != null;
        }
    }

    private final FakeAuthorizationRepository authorization;
    private final Map<UUID, StoredAccount> accounts = new LinkedHashMap<>();
    private final Map<UUID, Group> groups = new LinkedHashMap<>();
    private final Map<UUID, Set<UUID>> members = new HashMap<>();

    public FakeDirectoryStore(FakeAuthorizationRepository authorization) {
        this.authorization = authorization;
    }

    @Override
    public synchronized Account saveAccount(UUID id, AccountData data) {
        var now = Instant.now();
        var existing = id != null ? live(id).orElseThrow(() -> ScimException.notFound("User"))
            : accounts.values().stream().filter(stored -> stored.deleted() && sameUserName(stored.account(), data.userName()))
                .map(StoredAccount::account).findFirst().orElse(null);
        var accountId = existing != null ? existing.id() : UUID.randomUUID();
        for (var other : accounts.values()) {
            if (!other.account().id().equals(accountId)
                    && (sameUserName(other.account(), data.userName()) || other.account().subject().equals(data.subject()))) {
                throw ScimException.uniqueness("userName or subject already taken");
            }
        }
        var account = new Account(accountId, data.subject(), data.userName(), data.externalId(), data.displayName(),
            data.active(), existing != null ? existing.created() : now, now);
        accounts.put(accountId, new StoredAccount(account, null));
        publish();
        return account;
    }

    private static boolean sameUserName(Account account, String userName) {
        return account.userName().equalsIgnoreCase(userName);
    }

    private Optional<Account> live(UUID id) {
        return Optional.ofNullable(accounts.get(id)).filter(stored -> !stored.deleted()).map(StoredAccount::account);
    }

    @Override
    public synchronized Optional<Account> account(UUID id) {
        return live(id);
    }

    @Override
    public synchronized Page<Account> accounts(ScimFilter filter, int startIndex, int count) {
        return page(accounts.values().stream().filter(stored -> !stored.deleted()).map(StoredAccount::account).toList(),
            account -> attributes(account), filter, startIndex, count);
    }

    @Override
    public synchronized Optional<Account> deleteAccount(UUID id) {
        var account = live(id);
        account.ifPresent(found -> {
            var blocked = new Account(found.id(), found.subject(), found.userName(), null, null,
                false, found.created(), Instant.now());
            accounts.put(id, new StoredAccount(blocked, Instant.now()));
            members.values().forEach(set -> set.remove(id));
            publish();
        });
        return account;
    }

    @Override
    public synchronized int purgeDeletedBefore(Instant before) {
        var purged = accounts.values().stream().filter(stored -> stored.deleted() && stored.deletedAt().isBefore(before))
            .map(stored -> stored.account().id()).toList();
        purged.forEach(accounts::remove);
        publish();
        return purged.size();
    }

    @Override
    public synchronized Group saveGroup(UUID id, GroupData data) {
        var now = Instant.now();
        var existing = id == null ? null : Optional.ofNullable(groups.get(id)).orElseThrow(() -> ScimException.notFound("Group"));
        var groupId = id != null ? id : UUID.randomUUID();
        if (groups.values().stream().anyMatch(other -> !other.id().equals(groupId) && other.idpKey().equals(data.idpKey()))) {
            throw ScimException.uniqueness("group '" + data.idpKey() + "' already exists");
        }
        var group = new Group(groupId, data.idpKey(), data.displayName(), data.externalId(),
            existing != null ? existing.created() : now, now);
        groups.put(groupId, group);
        members.putIfAbsent(groupId, new HashSet<>());
        publish();
        return group;
    }

    @Override
    public synchronized Optional<Group> group(UUID id) {
        return Optional.ofNullable(groups.get(id));
    }

    @Override
    public synchronized Page<Group> groups(ScimFilter filter, int startIndex, int count) {
        return page(List.copyOf(groups.values()), this::attributes, filter, startIndex, count);
    }

    @Override
    public synchronized List<Member> members(UUID groupId) {
        return members.getOrDefault(groupId, Set.of()).stream()
            .map(id -> new Member(id, accounts.get(id).account().displayName()))
            .toList();
    }

    @Override
    public synchronized void replaceMembers(UUID groupId, Set<UUID> accountIds) {
        members.put(groupId, new HashSet<>());
        addMembers(groupId, accountIds);
    }

    @Override
    public synchronized void addMembers(UUID groupId, Set<UUID> accountIds) {
        accountIds.stream().filter(id -> live(id).isPresent())
            .forEach(id -> members.computeIfAbsent(groupId, g -> new HashSet<>()).add(id));
        publish();
    }

    @Override
    public synchronized void removeMembers(UUID groupId, Set<UUID> accountIds) {
        members.getOrDefault(groupId, new HashSet<>()).removeAll(accountIds);
        publish();
    }

    @Override
    public synchronized boolean deleteGroup(UUID id) {
        var removed = groups.remove(id) != null;
        members.remove(id);
        publish();
        return removed;
    }

    @Override
    public synchronized Set<String> idpKeysOf(UUID accountId) {
        return members.entrySet().stream().filter(entry -> entry.getValue().contains(accountId))
            .map(entry -> groups.get(entry.getKey()).idpKey()).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public synchronized Set<String> blockedSubjects() {
        return accounts.values().stream().filter(stored -> !stored.account().active())
            .map(stored -> stored.account().subject()).collect(Collectors.toUnmodifiableSet());
    }

    private void publish() {
        var groupsBySubject = new HashMap<String, Set<String>>();
        accounts.values().forEach(stored -> groupsBySubject.put(stored.account().subject(), idpKeysOf(stored.account().id())));
        authorization.directory(groupsBySubject, blockedSubjects());
    }

    private Map<String, Object> attributes(Account account) {
        var attributes = new HashMap<String, Object>();
        attributes.put("id", account.id().toString());
        attributes.put("userName", account.userName());
        attributes.put("active", account.active());
        if (account.externalId() != null) {
            attributes.put("externalId", account.externalId());
        }
        if (account.displayName() != null) {
            attributes.put("displayName", account.displayName());
        }
        return attributes;
    }

    private Map<String, Object> attributes(Group group) {
        var attributes = new HashMap<String, Object>();
        attributes.put("id", group.id().toString());
        attributes.put("displayName", group.displayName());
        if (group.externalId() != null) {
            attributes.put("externalId", group.externalId());
        }
        attributes.put("members", members.getOrDefault(group.id(), Set.of()).stream()
            .map(id -> Map.<String, Object>of("value", id.toString())).toList());
        return attributes;
    }

    private static <T> Page<T> page(List<T> all, Function<T, Map<String, Object>> attributes, ScimFilter filter,
                                    int startIndex, int count) {
        var matching = new ArrayList<T>();
        for (var item : all) {
            if (filter == null || filter.matches(attributes.apply(item)::get)) {
                matching.add(item);
            }
        }
        var from = Math.min(Math.max(startIndex, 1) - 1, matching.size());
        var to = Math.min(from + Math.max(count, 0), matching.size());
        return new Page<>(List.copyOf(matching.subList(from, to)), matching.size());
    }
}
