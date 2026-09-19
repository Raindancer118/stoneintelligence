<script lang="ts">
  import { onMount } from "svelte";
  import { api, type Group, type PathRule, type Role, type Vault } from "../api";
  import { connectionConfigJson } from "../connectionConfig";

  let { vault }: { vault: Vault } = $props();

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
      error = null;
      roles = await api.listRoles(vault.id);
    } catch (e) {
      error = (e as Error).message;
    }
  }

  async function createGroup() {
    if (!newGroupName.trim()) return;
    try {
      await api.createGroup(vault.id, newGroupName.trim());
      newGroupName = "";
      error = null;
      groups = await api.listGroups(vault.id);
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
      error = null;
      groups = await api.listGroups(vault.id);
    } catch (e) {
      error = (e as Error).message;
    }
  }

  async function removeMember(groupId: string, subject: string) {
    try {
      await api.removeMember(vault.id, groupId, subject);
      error = null;
      groups = await api.listGroups(vault.id);
    } catch (e) {
      error = (e as Error).message;
    }
  }

  async function toggleRoleAssignment(groupId: string, roleId: string, assigned: boolean) {
    try {
      if (assigned) await api.unassignRole(vault.id, groupId, roleId);
      else await api.assignRole(vault.id, groupId, roleId);
      error = null;
      groups = await api.listGroups(vault.id);
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
      error = null;
      pathRules = await api.listPathRules(vault.id);
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

<header>
  <h1>{vault.name}</h1>
  <span class="mono id">{vault.id}</span>
</header>

{#if error}
  <p class="error">{error}</p>
{/if}

<section>
  <h3>Plugin-Verbindung</h3>
  <p class="hint">Konfiguration fürs Obsidian-Plugin - dort unter „Verbindungsdaten einfügen".</p>
  <button class="secondary" onclick={copyConfig}>{configCopied ? "Kopiert" : "Konfiguration kopieren"}</button>
</section>

<section>
  <h3>Rollen</h3>
  {#if roles.length === 0}
    <p class="hint">Noch keine Rollen.</p>
  {:else}
    <table>
      <tbody>
        {#each roles as role (role.id)}
          <tr>
            <td class="label">{role.name}</td>
            <td class="permissions">{role.permissions.join(", ")}</td>
          </tr>
        {/each}
      </tbody>
    </table>
  {/if}
  <form onsubmit={(e) => { e.preventDefault(); createRole(); }}>
    <input type="text" placeholder="Rollenname" bind:value={newRoleName} />
    <fieldset>
      {#each ALL_PERMISSIONS as permission}
        <label>
          <input type="checkbox" checked={newRolePermissions.has(permission)} onchange={() => togglePermission(permission)} />
          {permission}
        </label>
      {/each}
    </fieldset>
    <button type="submit">Rolle anlegen</button>
  </form>
</section>

<section>
  <h3>Gruppen</h3>
  {#each groups as group (group.id)}
    <div class="group">
      <h4>{group.name}</h4>

      <ul class="members">
        {#each group.memberSubjects as subject}
          <li>
            {subject}
            <button class="link" onclick={() => removeMember(group.id, subject)}>entfernen</button>
          </li>
        {/each}
        {#if group.memberSubjects.length === 0}
          <li class="hint">Keine Mitglieder.</li>
        {/if}
      </ul>
      <form class="row-form" onsubmit={(e) => { e.preventDefault(); addMember(group.id); }}>
        <input
          type="text"
          placeholder="Actor (z. B. tom)"
          value={newMemberByGroup[group.id] ?? ""}
          oninput={(e) => (newMemberByGroup = { ...newMemberByGroup, [group.id]: e.currentTarget.value })}
        />
        <button class="secondary" type="submit">Hinzufügen</button>
      </form>

      <fieldset class="roles-for-group">
        {#each roles as role (role.id)}
          {@const assigned = group.roleIds.includes(role.id)}
          <label>
            <input type="checkbox" checked={assigned} onchange={() => toggleRoleAssignment(group.id, role.id, assigned)} />
            {role.name}
          </label>
        {/each}
      </fieldset>
    </div>
  {/each}
  <form onsubmit={(e) => { e.preventDefault(); createGroup(); }}>
    <input type="text" placeholder="Gruppenname" bind:value={newGroupName} />
    <button type="submit">Gruppe anlegen</button>
  </form>
</section>

<section>
  <h3>Ordner-ACLs</h3>
  {#if pathRules.length === 0}
    <p class="hint">Keine Regeln - ohne Regel ist ein Pfad standardmäßig erlaubt.</p>
  {:else}
    <table>
      <tbody>
        {#each pathRules as rule}
          <tr>
            <td class="mono">{rule.pathPrefix}</td>
            <td>{rule.scopeSubject ?? "jeder"}</td>
            <td class="effect" class:deny={rule.effect === "DENY"}>{rule.effect}</td>
          </tr>
        {/each}
      </tbody>
    </table>
  {/if}
  <form class="row-form" onsubmit={(e) => { e.preventDefault(); createPathRule(); }}>
    <input type="text" placeholder="Pfad-Präfix" bind:value={newPathRule.pathPrefix} />
    <input type="text" placeholder="Actor (leer = jeder)" bind:value={newPathRule.scopeSubject} />
    <select bind:value={newPathRule.effect}>
      <option value="DENY">DENY</option>
      <option value="ALLOW">ALLOW</option>
    </select>
    <button class="secondary" type="submit">Regel anlegen</button>
  </form>
</section>

<style>
  header {
    display: flex;
    flex-wrap: wrap;
    align-items: baseline;
    gap: 0.5rem 0.75rem;
    margin-bottom: 2rem;
  }

  header h1 {
    font-size: 1.6rem;
  }

  .id {
    color: var(--ink-dim);
    word-break: break-all;
  }

  .error {
    color: var(--rust);
  }

  section {
    background: var(--surface);
    border-radius: var(--radius);
    box-shadow: 0 1px 2px rgba(33, 31, 26, 0.06), 0 1px 0 var(--line);
    padding: 1.5rem 1.75rem;
    margin-bottom: 1.5rem;
  }

  h3 {
    font-size: 0.85rem;
    text-transform: uppercase;
    letter-spacing: 0.06em;
    color: var(--ink-dim);
  }

  h4 {
    font-size: 1rem;
    margin-bottom: 0.5rem;
  }

  .hint {
    color: var(--ink-dim);
    font-size: 0.9rem;
  }

  table {
    width: 100%;
    border-collapse: collapse;
    margin-bottom: 1rem;
  }

  td {
    padding: 0.5rem 0;
    border-bottom: 1px solid var(--line);
  }

  .label {
    font-weight: 600;
  }

  .permissions {
    color: var(--ink-dim);
    text-align: right;
  }

  .effect {
    text-align: right;
    font-weight: 600;
    color: var(--forest);
  }

  .effect.deny {
    color: var(--rust);
  }

  form {
    display: flex;
    flex-wrap: wrap;
    gap: 0.6rem;
    align-items: center;
  }

  .row-form {
    margin-top: 0.6rem;
  }

  input[type="text"] {
    background: var(--surface-raised);
    border: 1px solid var(--line);
    color: var(--ink);
    padding: 0.5rem 0.7rem;
    border-radius: var(--radius);
  }

  select {
    background: var(--surface-raised);
    border: 1px solid var(--line);
    color: var(--ink);
    padding: 0.5rem 0.7rem;
    border-radius: var(--radius);
  }

  input:focus,
  select:focus {
    outline: none;
    border-color: var(--forest);
  }

  fieldset {
    display: flex;
    flex-wrap: wrap;
    gap: 0.6rem 1rem;
    border: none;
    padding: 0;
    margin: 0;
    color: var(--ink-dim);
    font-size: 0.88rem;
  }

  fieldset label {
    display: flex;
    align-items: center;
    gap: 0.35rem;
  }

  button {
    background: var(--forest);
    color: var(--surface);
    border: none;
    padding: 0.55rem 1.1rem;
    border-radius: var(--radius);
    font-weight: 600;
  }

  button:hover {
    background: var(--forest-hover);
  }

  button.secondary {
    background: none;
    color: var(--ink);
    border: 1px solid var(--line);
  }

  button.secondary:hover {
    border-color: var(--forest);
    color: var(--forest);
  }

  button.link {
    background: none;
    border: none;
    color: var(--ink-dim);
    padding: 0;
    font-size: 0.8rem;
    text-decoration: underline;
    min-height: 0;
  }

  .group {
    border-top: 1px solid var(--line);
    padding-top: 1.1rem;
    margin-top: 1.1rem;
  }

  .group:first-child {
    border-top: none;
    padding-top: 0;
    margin-top: 0;
  }

  .members {
    list-style: none;
    padding: 0;
    margin: 0 0 0.6rem;
    display: flex;
    flex-direction: column;
    gap: 0.3rem;
  }

  .members li {
    display: flex;
    justify-content: space-between;
    font-size: 0.92rem;
  }

  .roles-for-group {
    margin-top: 0.8rem;
  }
</style>
