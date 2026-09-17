<script lang="ts">
  import { onMount } from "svelte";
  import { api, type Group, type PathRule, type Role, type Vault } from "../api";
  import { connectionConfigJson } from "../connectionConfig";

  let { vault, onBack }: { vault: Vault; onBack: () => void } = $props();

  const ALL_PERMISSIONS = ["READ", "WRITE", "DELETE", "CREATE"];

  let roles = $state<Role[]>([]);
  let groups = $state<Group[]>([]);
  let pathRules = $state<PathRule[]>([]);
  let error = $state<string | null>(null);
  let configCopied = $state(false);

  let newRoleName = $state("");
  let newRolePermissions = $state<Set<string>>(new Set());
  let newGroupName = $state("");
  let newMemberByGroup = $state<Record<string, string>>({});
  let newPathRule = $state({ pathPrefix: "", scopeSubject: "", effect: "DENY" as "ALLOW" | "DENY" });

  async function refresh() {
    try {
      [roles, groups, pathRules] = await Promise.all([
        api.listRoles(vault.id),
        api.listGroups(vault.id),
        api.listPathRules(vault.id),
      ]);
    } catch (e) {
      error = (e as Error).message;
    }
  }

  onMount(refresh);

  function togglePermission(permission: string) {
    const next = new Set(newRolePermissions);
    if (next.has(permission)) next.delete(permission);
    else next.add(permission);
    newRolePermissions = next;
  }

  async function createRole() {
    if (!newRoleName.trim() || newRolePermissions.size === 0) return;
    try {
      await api.createRole(vault.id, newRoleName.trim(), [...newRolePermissions]);
      newRoleName = "";
      newRolePermissions = new Set();
      await refresh();
    } catch (e) {
      error = (e as Error).message;
    }
  }

  async function createGroup() {
    if (!newGroupName.trim()) return;
    try {
      await api.createGroup(vault.id, newGroupName.trim());
      newGroupName = "";
      await refresh();
    } catch (e) {
      error = (e as Error).message;
    }
  }

  async function addMember(groupId: string) {
    const subject = (newMemberByGroup[groupId] ?? "").trim();
    if (!subject) return;
    try {
      await api.addMember(vault.id, groupId, subject);
      newMemberByGroup = { ...newMemberByGroup, [groupId]: "" };
      await refresh();
    } catch (e) {
      error = (e as Error).message;
    }
  }

  async function removeMember(groupId: string, subject: string) {
    try {
      await api.removeMember(vault.id, groupId, subject);
      await refresh();
    } catch (e) {
      error = (e as Error).message;
    }
  }

  async function toggleRoleAssignment(groupId: string, roleId: string, assigned: boolean) {
    try {
      if (assigned) await api.unassignRole(vault.id, groupId, roleId);
      else await api.assignRole(vault.id, groupId, roleId);
      await refresh();
    } catch (e) {
      error = (e as Error).message;
    }
  }

  async function createPathRule() {
    if (!newPathRule.pathPrefix.trim()) return;
    try {
      await api.createPathRule(
        vault.id,
        newPathRule.pathPrefix.trim(),
        newPathRule.scopeSubject.trim() || null,
        newPathRule.effect,
      );
      newPathRule = { pathPrefix: "", scopeSubject: "", effect: "DENY" };
      await refresh();
    } catch (e) {
      error = (e as Error).message;
    }
  }

  async function copyConfig() {
    await navigator.clipboard.writeText(connectionConfigJson(vault.id));
    configCopied = true;
    setTimeout(() => (configCopied = false), 2000);
  }
</script>

<button class="back" onclick={onBack}>← Zurück</button>
<h2>{vault.name} <span class="mono dim">{vault.id}</span></h2>

{#if error}
  <p class="error">{error}</p>
{/if}

<section class="panel">
  <h3>Plugin-Verbindung</h3>
  <p class="dim">Konfiguration fürs Obsidian-Plugin - dort unter „Verbindungsdaten einfügen" einfügen.</p>
  <button class="ghost" onclick={copyConfig}>{configCopied ? "Kopiert ✓" : "Konfiguration kopieren"}</button>
</section>

<section class="panel">
  <h3>Rollen</h3>
  <ul class="entity-list">
    {#each roles as role (role.id)}
      <li>
        <span class="entity-name">{role.name}</span>
        <span class="tags">
          {#each role.permissions as permission}
            <span class="tag">{permission}</span>
          {/each}
        </span>
      </li>
    {/each}
  </ul>
  <form class="inline-form" onsubmit={(e) => { e.preventDefault(); createRole(); }}>
    <input type="text" placeholder="Rollenname" bind:value={newRoleName} />
    <span class="checkboxes">
      {#each ALL_PERMISSIONS as permission}
        <label>
          <input
            type="checkbox"
            checked={newRolePermissions.has(permission)}
            onchange={() => togglePermission(permission)}
          />
          {permission}
        </label>
      {/each}
    </span>
    <button class="primary" type="submit">Anlegen</button>
  </form>
</section>

<section class="panel">
  <h3>Gruppen</h3>
  {#each groups as group (group.id)}
    <div class="group-block">
      <h4>{group.name}</h4>
      <div class="members">
        {#each group.memberSubjects as subject}
          <span class="tag member">
            {subject}
            <button class="tag-remove" onclick={() => removeMember(group.id, subject)}>×</button>
          </span>
        {/each}
      </div>
      <form class="inline-form" onsubmit={(e) => { e.preventDefault(); addMember(group.id); }}>
        <input
          type="text"
          placeholder="Actor (z. B. tom)"
          value={newMemberByGroup[group.id] ?? ""}
          oninput={(e) => (newMemberByGroup = { ...newMemberByGroup, [group.id]: e.currentTarget.value })}
        />
        <button class="ghost" type="submit">Mitglied hinzufügen</button>
      </form>
      <div class="role-toggles">
        {#each roles as role (role.id)}
          {@const assigned = group.roleIds.includes(role.id)}
          <label class="role-toggle" class:assigned>
            <input
              type="checkbox"
              checked={assigned}
              onchange={() => toggleRoleAssignment(group.id, role.id, assigned)}
            />
            {role.name}
          </label>
        {/each}
      </div>
    </div>
  {/each}
  <form class="inline-form" onsubmit={(e) => { e.preventDefault(); createGroup(); }}>
    <input type="text" placeholder="Gruppenname" bind:value={newGroupName} />
    <button class="primary" type="submit">Anlegen</button>
  </form>
</section>

<section class="panel">
  <h3>Ordner-ACLs</h3>
  <ul class="entity-list">
    {#each pathRules as rule}
      <li>
        <span class="mono entity-name">{rule.pathPrefix}</span>
        <span class="tags">
          <span class="tag">{rule.scopeSubject ?? "jeder"}</span>
          <span class="tag" class:deny={rule.effect === "DENY"}>{rule.effect}</span>
        </span>
      </li>
    {/each}
  </ul>
  <form class="inline-form" onsubmit={(e) => { e.preventDefault(); createPathRule(); }}>
    <input type="text" placeholder="Pfad-Präfix (z. B. privat)" bind:value={newPathRule.pathPrefix} />
    <input type="text" placeholder="Actor (leer = jeder)" bind:value={newPathRule.scopeSubject} />
    <select bind:value={newPathRule.effect}>
      <option value="DENY">DENY</option>
      <option value="ALLOW">ALLOW</option>
    </select>
    <button class="primary" type="submit">Regel anlegen</button>
  </form>
</section>

<style>
  .back {
    background: none;
    border: none;
    color: var(--paper-dim);
    padding: 0;
    margin-bottom: 1rem;
  }

  .back:hover {
    color: var(--paper);
  }

  .panel {
    background: var(--ink-raised);
    border: 1px solid var(--ink-line);
    border-radius: var(--radius);
    padding: 1.5rem 1.75rem;
    margin-bottom: 1.5rem;
  }

  .dim {
    color: var(--paper-dim);
  }

  .error {
    color: var(--rust);
  }

  .entity-list {
    list-style: none;
    margin: 0 0 1rem;
    padding: 0;
    display: flex;
    flex-direction: column;
    gap: 0.4rem;
  }

  .entity-list li {
    display: flex;
    justify-content: space-between;
    align-items: center;
    background: var(--ink);
    border: 1px solid var(--ink-line);
    border-radius: var(--radius);
    padding: 0.5rem 0.8rem;
  }

  .entity-name {
    font-weight: 600;
  }

  .tags {
    display: flex;
    gap: 0.4rem;
  }

  .tag {
    font-size: 0.75rem;
    background: var(--ink-line);
    color: var(--paper-dim);
    padding: 0.15rem 0.5rem;
    border-radius: 999px;
  }

  .tag.deny {
    background: color-mix(in srgb, var(--rust) 30%, var(--ink-line));
    color: var(--paper);
  }

  .inline-form {
    display: flex;
    flex-wrap: wrap;
    gap: 0.6rem;
    align-items: center;
    margin-top: 0.75rem;
  }

  input[type="text"] {
    background: var(--ink);
    border: 1px solid var(--ink-line);
    color: var(--paper);
    padding: 0.5rem 0.7rem;
    border-radius: var(--radius);
  }

  input:focus,
  select:focus {
    outline: none;
    border-color: var(--ember-dim);
  }

  select {
    background: var(--ink);
    border: 1px solid var(--ink-line);
    color: var(--paper);
    padding: 0.5rem 0.7rem;
    border-radius: var(--radius);
  }

  .checkboxes {
    display: flex;
    gap: 0.8rem;
    font-size: 0.85rem;
    color: var(--paper-dim);
  }

  .checkboxes label {
    display: flex;
    align-items: center;
    gap: 0.3rem;
  }

  button.primary {
    background: var(--ember);
    color: var(--ink);
    border: none;
    padding: 0.5rem 1.1rem;
    border-radius: var(--radius);
    font-weight: 600;
  }

  button.primary:hover {
    background: #e88a4f;
  }

  button.ghost {
    background: transparent;
    color: var(--paper);
    border: 1px solid var(--ink-line);
    padding: 0.5rem 1rem;
    border-radius: var(--radius);
  }

  button.ghost:hover {
    border-color: var(--ember-dim);
  }

  .group-block {
    border-top: 1px solid var(--ink-line);
    padding-top: 1rem;
    margin-top: 1rem;
  }

  .group-block:first-child {
    border-top: none;
    padding-top: 0;
    margin-top: 0;
  }

  .group-block h4 {
    margin: 0 0 0.5rem;
  }

  .members {
    display: flex;
    flex-wrap: wrap;
    gap: 0.4rem;
    margin-bottom: 0.5rem;
  }

  .tag.member {
    display: flex;
    align-items: center;
    gap: 0.3rem;
  }

  .tag-remove {
    background: none;
    border: none;
    color: inherit;
    padding: 0;
    line-height: 1;
    font-size: 0.9rem;
  }

  .role-toggles {
    display: flex;
    flex-wrap: wrap;
    gap: 0.5rem;
    margin-top: 0.6rem;
  }

  .role-toggle {
    display: flex;
    align-items: center;
    gap: 0.3rem;
    font-size: 0.8rem;
    color: var(--paper-dim);
    border: 1px solid var(--ink-line);
    border-radius: 999px;
    padding: 0.2rem 0.7rem;
  }

  .role-toggle.assigned {
    color: var(--moss);
    border-color: var(--moss);
  }
</style>
