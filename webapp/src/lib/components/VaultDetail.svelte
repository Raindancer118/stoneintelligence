<script lang="ts">
  import { onMount } from "svelte";
  import { api, ApiError, type Group, type Role, type Vault, type VaultMember } from "../api";
  import { explainAccessError, permissionsLabel } from "../accessPlan";
  import { describeEvent, formatDate, type HistoryEvent } from "../historyText";
  import { connectionConfigJson } from "../connectionConfig";
  import InvitePeople from "./InvitePeople.svelte";

  let { vault, me = "" }: { vault: Vault; me?: string } = $props();

  const ALL_PERMISSIONS = ["READ", "WRITE", "DELETE", "CREATE", "MANAGE"];

  let roles = $state<Role[]>([]);
  let groups = $state<Group[]>([]);
  let members = $state<VaultMember[]>([]);
  let log = $state<HistoryEvent[] | null>(null);
  let editingRole = $state<Record<string, string>>({});
  let editingGroup = $state<Record<string, string>>({});
  let error = $state<string | null>(null);
  let configCopied = $state(false);

  let newRoleName = $state("");
  let newRolePermissions = $state<Set<string>>(new Set());
  let newGroupName = $state("");
  let newMemberByGroup = $state<Record<string, string>>({});

  async function refresh() {
    try {
      [roles, groups, members] = await Promise.all([
        api.listRoles(vault.id),
        api.listGroups(vault.id),
        api.listMembers(vault.id),
      ]);
    } catch (e) {
      error = (e as Error).message;
    }
  }

  onMount(refresh);

  async function loadLog() {
    try { log = await api.vaultLog(vault.id, "", 50); }
    catch (e) { error = e instanceof ApiError ? explainAccessError(e.status) : (e as Error).message; }
  }

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

  /** Fuehrt eine Verwaltungsaenderung aus und laedt danach nur, was sie betrifft. */
  async function change(action: () => Promise<unknown>, reload: ("roles" | "groups" | "members")[]) {
    try {
      await action();
      error = null;
      for (const part of reload) {
        if (part === "roles") roles = await api.listRoles(vault.id);
        if (part === "groups") groups = await api.listGroups(vault.id);
        if (part === "members") members = await api.listMembers(vault.id);
      }
    } catch (e) {
      error = e instanceof ApiError ? explainAccessError(e.status) : (e as Error).message;
    }
  }

  function removeFromVault(subject: string) {
    const self = subject === me;
    if (!window.confirm(self ? "Den Vault verlassen? Du verlierst den Zugriff auf alle Notizen, bis dich jemand wieder einlädt."
      : `${subject} aus dem Vault nehmen? Alle Gruppen und persönlichen Freigaben fallen weg.`)) return;
    void change(() => api.removeFromVault(vault.id, subject), ["members", "groups"]);
  }

  function toggleRolePermission(role: Role, permission: string) {
    const next = role.permissions.includes(permission) ? role.permissions.filter(p => p !== permission) : [...role.permissions, permission];
    void change(() => api.updateRole(vault.id, role.id, null, next), ["roles", "members"]);
  }

  function renameRole(role: Role) {
    const name = (editingRole[role.id] ?? "").trim();
    if (!name || name === role.name) return;
    void change(() => api.updateRole(vault.id, role.id, name, null), ["roles"]);
  }

  function deleteRole(role: Role) {
    if (!window.confirm(`Rolle „${role.name}“ löschen? Gruppen mit dieser Rolle verlieren deren Rechte.`)) return;
    void change(() => api.deleteRole(vault.id, role.id), ["roles", "groups", "members"]);
  }

  function renameGroup(group: Group) {
    const name = (editingGroup[group.id] ?? "").trim();
    if (!name || name === group.name) return;
    void change(() => api.renameGroup(vault.id, group.id, name), ["groups", "members"]);
  }

  function deleteGroup(group: Group) {
    if (!window.confirm(`Gruppe „${group.name}“ löschen? Ihre Mitglieder verlieren die Rechte daraus, ihre Freigaben fallen weg.`)) return;
    void change(() => api.deleteGroup(vault.id, group.id), ["groups", "members"]);
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

<InvitePeople {vault} />

<section>
  <h3>Mitglieder</h3>
  <table>
    <tbody>
      {#each members as member (member.subject)}
        <tr>
          <td class="label">{member.subject}{member.subject === me ? " (du)" : ""}</td>
          <td class="permissions">{permissionsLabel(member.permissions)}{member.groups.length ? ` · ${member.groups.map(g => g.name).join(", ")}` : ""}</td>
          <td class="actions"><button class="link" onclick={() => removeFromVault(member.subject)}>{member.subject === me ? "Vault verlassen" : "entfernen"}</button></td>
        </tr>
      {/each}
    </tbody>
  </table>
</section>

<section>
  <h3>Plugin-Verbindung</h3>
  <p class="hint">Für die gehostete Instanz reicht der Reiter „In Obsidian“ – ein Klick verbindet das Plugin. Diese Konfiguration brauchst du nur für einen eigenen Server (Plugin: Erweitert → Verbindungsdaten einfügen).</p>
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
            <td class="label"><input aria-label={`Name der Rolle ${role.name}`} value={editingRole[role.id] ?? role.name}
              oninput={(e) => (editingRole = { ...editingRole, [role.id]: e.currentTarget.value })} onchange={() => renameRole(role)} /></td>
            <td class="permissions">
              {#each ALL_PERMISSIONS as permission}
                <label class="inline"><input type="checkbox" checked={role.permissions.includes(permission)} onchange={() => toggleRolePermission(role, permission)} />{permission}</label>
              {/each}
            </td>
            <td class="actions"><button class="link" onclick={() => deleteRole(role)}>löschen</button></td>
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
      <div class="group-head">
        <input aria-label={`Name der Gruppe ${group.name}`} value={editingGroup[group.id] ?? group.name}
          oninput={(e) => (editingGroup = { ...editingGroup, [group.id]: e.currentTarget.value })} onchange={() => renameGroup(group)} />
        <button class="link" onclick={() => deleteGroup(group)}>Gruppe löschen</button>
      </div>
      <h4 class="visually-hidden">{group.name}</h4>

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
  <h3>Protokoll</h3>
  {#if log === null}
    <button class="secondary" onclick={loadLog}>Letzte Ereignisse anzeigen</button>
  {:else if log.length === 0}
    <p class="hint">Noch nichts, was du sehen darfst.</p>
  {:else}
    <ol class="log">{#each log as event}<li><span>{describeEvent(event)}</span><span class="hint">{formatDate(event.occurredAt)}</span></li>{/each}</ol>
  {/if}
</section>

<section>
  <h3>Freigaben</h3>
  <p class="hint">Wer welche Notiz oder welchen Ordner sehen und bearbeiten darf, legst du jetzt direkt dort fest: im Reiter „Notizen“ über „Freigabe“ – oder in Obsidian per Rechtsklick → „Freigabe…“.</p>
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

  .actions { text-align: right; white-space: nowrap; }
  .log { list-style: none; margin: 0; padding: 0; } .log li { display: flex; flex-direction: column; padding: .55rem 0; border-top: 1px solid var(--line); } .log .hint { font-size: .8rem; }
  .inline { display: inline-flex; align-items: center; gap: .25rem; margin-right: .6rem; font-size: .8rem; }
  .group-head { display: flex; align-items: center; justify-content: space-between; gap: .75rem; margin-bottom: .5rem; }
  .group-head input { font-weight: 600; }
  .visually-hidden { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); }

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


  input:focus,

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
