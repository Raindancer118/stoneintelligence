<script lang="ts">
  import { api, type Vault } from "../api";
  let { vaults, selectedId, onSelect, onCreated, canCreate = () => true }: {
    vaults: Vault[]; selectedId: string | null; onSelect: (vault: Vault) => void;
    onCreated: (vault: Vault) => Promise<void>; canCreate?: () => boolean;
  } = $props();
  let newVaultName = $state("");
  let error = $state("");
  let creating = $state(false);
  let showCreate = $state(false);
  async function createVault() {
    if (!newVaultName.trim() || creating || !canCreate()) return;
    creating = true; error = "";
    try { const created = await api.createVault(newVaultName.trim()); await onCreated(created); newVaultName = ""; showCreate = false; }
    catch (e) { error = e instanceof Error ? e.message : "Der Vault konnte nicht angelegt werden."; }
    finally { creating = false; }
  }
</script>
<nav aria-label="Vault-Auswahl"><div class="nav-title"><h2>Deine Vaults</h2><span>{vaults.length}</span></div>
  {#if !vaults.length}<p class="hint">Noch kein Vault vorhanden.</p>{:else}<ul>{#each vaults as vault (vault.id)}<li><button class="vault-row" class:active={vault.id === selectedId} aria-current={vault.id === selectedId ? "true" : undefined} onclick={() => onSelect(vault)}><span class="vault-mark" aria-hidden="true">{vault.name.charAt(0).toLocaleUpperCase()}</span><span>{vault.name}</span></button></li>{/each}</ul>{/if}
  <button class="add-vault quiet" aria-expanded={showCreate} onclick={() => showCreate = !showCreate}>+ Neuer Vault</button>
  {#if showCreate || !vaults.length}<form onsubmit={e => { e.preventDefault(); void createVault(); }}><label for="vault-name">Vault-Name</label><input id="vault-name" type="text" maxlength="100" placeholder="z. B. Team-Wissen" bind:value={newVaultName} disabled={creating} required /><button class="primary" disabled={creating}>{creating ? "Wird angelegt…" : "Vault anlegen"}</button></form>{/if}
  {#if error}<p class="error" role="alert">{error}</p>{/if}
  <p class="sidebar-note">Jeder Vault ist ein eigener Bereich für deine Notizen und dein Team.</p>
</nav>
<style>
  nav { padding: 1.7rem .9rem; } .nav-title { display: flex; align-items: center; justify-content: space-between; padding: 0 .5rem; margin-bottom: 1rem; } h2 { font-size: .85rem; margin: 0; } .nav-title > span { color: var(--ink-dim); font-size: .8rem; }
  ul { list-style: none; padding: 0; margin: 0 0 .8rem; } li { margin: .2rem 0; } .vault-row { display: flex; align-items: center; gap: .7rem; width: 100%; text-align: left; border: 0; border-radius: 5px; padding: .55rem .5rem; color: var(--ink); background: none; font-size: .88rem; overflow-wrap: anywhere; } .vault-row:hover { background: var(--surface-raised); } .vault-row.active { background: var(--forest-soft); color: var(--forest); font-weight: 700; }
  .vault-mark { display: grid; place-content: center; width: 28px; height: 30px; flex-shrink: 0; border: 1px solid var(--line); border-radius: 4px; background: var(--surface-raised); font-size: .8rem; }
  .add-vault { width: 100%; text-align: left; padding-left: .5rem; font-size: .85rem; } form { display: flex; flex-direction: column; gap: .5rem; padding: .75rem .5rem; } label { font-size: .8rem; } input { width: 100%; min-width: 0; } .sidebar-note { font-size: .75rem; line-height: 1.6; color: var(--ink-dim); margin: 2.5rem .5rem 0; } .error { font-size: .85rem; overflow-wrap: anywhere; }
  @media (max-width: 850px) { nav { padding: .75rem 1rem; display: flex; align-items: center; flex-wrap: wrap; gap: .5rem 1rem; } .nav-title { margin: 0; gap: .5rem; } ul { display: flex; gap: .4rem; overflow: auto; max-width: 100%; margin: 0; } .vault-row { white-space: nowrap; } .add-vault { width: auto; } .sidebar-note { display: none; } form { flex-direction: row; flex-wrap: wrap; align-items: center; } }
</style>
