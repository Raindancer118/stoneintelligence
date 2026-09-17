<script lang="ts">
  import { onMount } from "svelte";
  import { api, type Vault } from "../api";
  import VaultDetail from "./VaultDetail.svelte";

  let vaults = $state<Vault[]>([]);
  let selected = $state<Vault | null>(null);
  let newVaultName = $state("");
  let loading = $state(true);
  let error = $state<string | null>(null);

  async function refresh() {
    try {
      vaults = await api.listVaults();
    } catch (e) {
      error = (e as Error).message;
    } finally {
      loading = false;
    }
  }

  async function createVault() {
    if (!newVaultName.trim()) return;
    try {
      const vault = await api.createVault(newVaultName.trim());
      newVaultName = "";
      await refresh();
      selected = vault;
    } catch (e) {
      error = (e as Error).message;
    }
  }

  onMount(refresh);
</script>

{#if selected}
  <VaultDetail vault={selected} onBack={() => (selected = null)} />
{:else}
  <section class="panel">
    <h2>Neuen Vault anlegen</h2>
    <form onsubmit={(e) => { e.preventDefault(); createVault(); }}>
      <input type="text" placeholder="Name des Vaults" bind:value={newVaultName} />
      <button class="primary" type="submit">Anlegen</button>
    </form>
  </section>

  <section class="panel">
    <h2>Deine Vaults</h2>
    {#if loading}
      <p class="dim">Laden…</p>
    {:else if error}
      <p class="error">{error}</p>
    {:else if vaults.length === 0}
      <p class="dim">Noch keine Vaults - leg oben deinen ersten an.</p>
    {:else}
      <ul class="vault-list">
        {#each vaults as vault (vault.id)}
          <li>
            <button class="vault-row" onclick={() => (selected = vault)}>
              <span class="vault-name">{vault.name}</span>
              <span class="mono dim vault-id">{vault.id}</span>
            </button>
          </li>
        {/each}
      </ul>
    {/if}
  </section>
{/if}

<style>
  .panel {
    background: var(--ink-raised);
    border: 1px solid var(--ink-line);
    border-radius: var(--radius);
    padding: 1.5rem 1.75rem;
    margin-bottom: 1.5rem;
  }

  form {
    display: flex;
    gap: 0.75rem;
  }

  input {
    flex: 1;
    background: var(--ink);
    border: 1px solid var(--ink-line);
    color: var(--paper);
    padding: 0.6rem 0.8rem;
    border-radius: var(--radius);
  }

  input:focus {
    outline: none;
    border-color: var(--ember-dim);
  }

  button.primary {
    background: var(--ember);
    color: var(--ink);
    border: none;
    padding: 0.6rem 1.2rem;
    border-radius: var(--radius);
    font-weight: 600;
    white-space: nowrap;
  }

  button.primary:hover {
    background: #e88a4f;
  }

  .vault-list {
    list-style: none;
    margin: 0;
    padding: 0;
    display: flex;
    flex-direction: column;
    gap: 0.5rem;
  }

  .vault-row {
    width: 100%;
    display: flex;
    justify-content: space-between;
    align-items: baseline;
    background: var(--ink);
    border: 1px solid var(--ink-line);
    color: var(--paper);
    padding: 0.75rem 1rem;
    border-radius: var(--radius);
    text-align: left;
  }

  .vault-row:hover {
    border-color: var(--ember-dim);
  }

  .vault-name {
    font-weight: 600;
  }

  .vault-id {
    font-size: 0.8rem;
  }

  .dim {
    color: var(--paper-dim);
  }

  .error {
    color: var(--rust);
  }
</style>
