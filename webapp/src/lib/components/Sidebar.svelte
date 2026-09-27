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
