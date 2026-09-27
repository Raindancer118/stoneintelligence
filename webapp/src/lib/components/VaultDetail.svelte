<script lang="ts">
  import { withTransition } from "../motion";
  import { onMount } from "svelte";
  import { api, ApiError, type Group, type Role, type Vault, type VaultMember } from "../api";
  import { explainAccessError, permissionsLabel } from "../accessPlan";
  import { describeEvent, formatDate, type HistoryEvent } from "../historyText";
  import { connectionConfigJson } from "../connectionConfig";
  import InvitePeople from "./InvitePeople.svelte";

  let { vault, me = "", onRenamed = () => {}, onLeft = () => {} }: { vault: Vault; me?: string; onRenamed?: (vault: Vault) => void; onLeft?: () => void } = $props();

  let page = $state("members");
  let loading = $state(true);
  let vaultName = $state("");
  let renamingVault = $state(false);
  const adminPages = [{ id: "members", label: "Mitglieder" }, { id: "roles", label: "Rollen" }, { id: "groups", label: "Gruppen" }, { id: "log", label: "Protokoll" }, { id: "settings", label: "Vault-Einstellungen" }];
  async function renameVault() {
    if (!vaultName.trim() || renamingVault) return;
    renamingVault = true;
    try { const renamed = await api.renameVault(vault.id, vaultName.trim()); onRenamed(renamed); vaultName = ""; error = null; }
    catch (e) { error = (e as Error).message; }
    finally { renamingVault = false; }
  }
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
    loading = true; error = null;
    try {
      [roles, groups, members] = await Promise.all([
        api.listRoles(vault.id),
        api.listGroups(vault.id),
        api.listMembers(vault.id),
      ]);
    } catch (e) {
      error = (e as Error).message;
    } finally { loading = false; }
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
    void change(async () => { await api.removeFromVault(vault.id, subject); if (self) onLeft(); }, ["members", "groups"]);
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
    try { await navigator.clipboard.writeText(connectionConfigJson(vault.id)); configCopied = true; }
    catch { error = "Die Zwischenablage ist nicht erreichbar. Versuche es erneut."; }
    setTimeout(() => (configCopied = false), 2000);
  }
</script>

<header class="admin-intro"><h2>Mitglieder & Rechte</h2><p>Ein guter Ort für Zusammenarbeit beginnt mit den richtigen Zugängen.</p></header>
<nav class="admin-tabs" aria-label="Verwaltungsbereiche">{#each adminPages as item}<button class:active={page === item.id} aria-pressed={page === item.id} onclick={() => void withTransition(() => page = item.id, "tab")}>{item.label}</button>{/each}</nav>
{#if loading}<p role="status">Verwaltung wird geladen…</p>{/if}

{#if error}
  <div class="feedback error" role="alert"><p>{error}</p><button class="secondary" onclick={refresh}>Erneut versuchen</button></div>
{/if}

{#if page === "members"}
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

{#if !members.length && !loading}<p class="hint">Noch keine Mitglieder sichtbar.</p>{/if}
{/if}
{#if page === "settings"}
<section class="admin-section"><h3>Vault umbenennen</h3><form onsubmit={e => { e.preventDefault(); void renameVault(); }}><label for="rename-vault">Neuer Vault-Name</label><input id="rename-vault" placeholder={vault.name} bind:value={vaultName} required maxlength="100" /><button class="primary" disabled={renamingVault}>Vault umbenennen</button></form></section>
<section>
  <h3>Plugin-Verbindung</h3>
  <p class="hint">Für die gehostete Instanz reicht der Reiter „In Obsidian“ – ein Klick verbindet das Plugin. Diese Konfiguration brauchst du nur für einen eigenen Server (Plugin: Erweitert → Verbindungsdaten einfügen).</p>
  <button class="secondary" onclick={copyConfig}>{configCopied ? "Kopiert" : "Konfiguration kopieren"}</button>
</section>

{/if}
{#if page === "roles"}
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
    <input type="text" aria-label="Rollenname" placeholder="Rollenname" bind:value={newRoleName} />
    <fieldset><legend>Berechtigungen</legend>
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

{/if}
{#if page === "groups"}
<section>
  <h3>Gruppen</h3>
  {#if !groups.length && !loading}<p class="hint">Noch keine Gruppen. Fasse Menschen zusammen, die dieselben Rechte brauchen.</p>{/if}
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
          aria-label={`Mitglied für ${group.name}`} placeholder="Benutzername"
          value={newMemberByGroup[group.id] ?? ""}
          oninput={(e) => (newMemberByGroup = { ...newMemberByGroup, [group.id]: e.currentTarget.value })}
        />
        <button class="secondary" type="submit">Hinzufügen</button>
      </form>

      <fieldset class="roles-for-group"><legend>Rollen dieser Gruppe</legend>
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
    <input type="text" aria-label="Gruppenname" placeholder="Gruppenname" bind:value={newGroupName} />
    <button type="submit">Gruppe anlegen</button>
  </form>
</section>

{/if}
{#if page === "log"}
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

{/if}
{#if page === "settings"}
<section>
  <h3>Freigaben</h3>
  <p class="hint">Wer welche Notiz oder welchen Ordner sehen und bearbeiten darf, legst du jetzt direkt dort fest: im Reiter „Notizen“ über „Freigabe“ – oder in Obsidian per Rechtsklick → „Freigabe…“.</p>
</section>


{/if}
