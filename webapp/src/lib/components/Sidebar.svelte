<script lang="ts">
  import { api, type Vault } from "../api";

  let {
    vaults,
    selectedId,
    onSelect,
    onCreated,
  }: {
    vaults: Vault[];
    selectedId: string | null;
    onSelect: (vault: Vault) => void;
    onCreated: () => void;
  } = $props();

  let newVaultName = $state("");
  let error = $state<string | null>(null);

  async function createVault() {
    if (!newVaultName.trim()) return;
    try {
      await api.createVault(newVaultName.trim());
      newVaultName = "";
      onCreated();
    } catch (e) {
      error = (e as Error).message;
    }
  }
</script>

<nav>
  <h2>Vaults</h2>
  {#if vaults.length === 0}
    <p class="empty">Noch keiner angelegt.</p>
  {:else}
    <ul>
      {#each vaults as vault (vault.id)}
        <li>
          <button class="row" class:active={vault.id === selectedId} onclick={() => onSelect(vault)}>
            {vault.name}
          </button>
        </li>
      {/each}
    </ul>
  {/if}

  <form onsubmit={(e) => { e.preventDefault(); createVault(); }}>
    <input type="text" placeholder="Neuer Vault-Name" bind:value={newVaultName} />
    <button type="submit">Anlegen</button>
  </form>
  {#if error}
    <p class="error">{error}</p>
  {/if}
</nav>

<style>
  nav {
    padding: 1.5rem 1.25rem;
  }

  h2 {
    font-size: 0.8rem;
    text-transform: uppercase;
    letter-spacing: 0.06em;
    color: var(--ink-dim);
    margin-bottom: 1rem;
  }

  .empty {
    color: var(--ink-dim);
    font-size: 0.9rem;
  }

  ul {
    list-style: none;
    margin: 0 0 1.25rem;
    padding: 0;
  }

  .row {
    display: block;
    width: 100%;
    text-align: left;
    background: none;
    border: none;
    border-radius: var(--radius);
    padding: 0.6rem 0.7rem;
    color: var(--ink);
    font-weight: 500;
  }

  .row:hover {
    background: var(--surface-raised);
  }

  .row.active {
    background: var(--forest);
    color: var(--surface);
  }

  form {
    display: flex;
    flex-direction: column;
    gap: 0.5rem;
    border-top: 1px solid var(--line);
    padding-top: 1rem;
  }

  input {
    background: var(--surface-raised);
    border: 1px solid var(--line);
    color: var(--ink);
    padding: 0.55rem 0.7rem;
    border-radius: var(--radius);
  }

  input:focus {
    outline: none;
    border-color: var(--forest);
  }

  button[type="submit"] {
    background: var(--forest);
    color: var(--surface);
    border: none;
    padding: 0.55rem;
    border-radius: var(--radius);
    font-weight: 600;
  }

  button[type="submit"]:hover {
    background: var(--forest-hover);
  }

  .error {
    color: var(--rust);
    font-size: 0.85rem;
  }
</style>
