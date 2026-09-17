<script lang="ts">
  import { onMount } from "svelte";
  import type { User } from "oidc-client-ts";
  import { completeLogin, getUser, login as startLogin, logout as startLogout, preferredUsername } from "./lib/auth";
  import { api, type Vault } from "./lib/api";
  import Sidebar from "./lib/components/Sidebar.svelte";
  import VaultDetail from "./lib/components/VaultDetail.svelte";

  let user = $state<User | null>(null);
  let loading = $state(true);
  let error = $state<string | null>(null);
  let vaults = $state<Vault[]>([]);
  let selected = $state<Vault | null>(null);

  async function refreshVaults() {
    vaults = await api.listVaults();
    if (selected) {
      selected = vaults.find((v) => v.id === selected!.id) ?? null;
    }
  }

  onMount(async () => {
    try {
      if (window.location.pathname === "/callback") {
        await completeLogin();
        window.history.replaceState({}, "", "/");
      }
      user = await getUser();
      if (user) {
        await refreshVaults();
      }
    } catch (e) {
      error = (e as Error).message;
    } finally {
      loading = false;
    }
  });
</script>

{#if loading}
  <p class="status">Laden…</p>
{:else if error}
  <p class="status error">{error}</p>
{:else if !user}
  <section class="gate">
    <h1>StoneIntelligence</h1>
    <p>Anmeldung über Authentik, um deine Vaults zu verwalten.</p>
    <button onclick={() => startLogin()}>Mit Authentik anmelden</button>
  </section>
{:else}
  <div class="shell">
    <header>
      <span class="brand">StoneIntelligence</span>
      <span class="greeting">Willkommen, {preferredUsername(user)}.</span>
      <button class="logout" onclick={() => startLogout()}>Abmelden</button>
    </header>
    <div class="body">
      <aside>
        <Sidebar {vaults} selectedId={selected?.id ?? null} onSelect={(v) => (selected = v)} onCreated={refreshVaults} />
      </aside>
      <main>
        {#if selected}
          {#key selected.id}
            <VaultDetail vault={selected} />
          {/key}
        {:else}
          <p class="status">Wähle links einen Vault, oder leg einen neuen an.</p>
        {/if}
      </main>
    </div>
  </div>
{/if}

<style>
  .status {
    padding: 3rem;
    color: var(--ink-dim);
  }

  .status.error {
    color: var(--rust);
  }

  .gate {
    max-width: 28rem;
    margin: 6rem auto 0;
    text-align: center;
  }

  .gate h1 {
    font-size: 2rem;
  }

  .gate p {
    color: var(--ink-dim);
    margin-bottom: 1.5rem;
  }

  .gate button {
    background: var(--forest);
    color: var(--surface);
    border: none;
    padding: 0.7rem 1.6rem;
    border-radius: var(--radius);
    font-weight: 700;
  }

  .gate button:hover {
    background: var(--forest-hover);
  }

  .shell {
    min-height: 100vh;
    display: flex;
    flex-direction: column;
  }

  header {
    display: flex;
    align-items: center;
    gap: 1rem;
    padding: 0.85rem 1.5rem;
    border-bottom: 1px solid var(--line);
    background: var(--surface);
  }

  .brand {
    font-weight: 700;
  }

  .greeting {
    color: var(--ink-dim);
    flex: 1;
  }

  .logout {
    background: none;
    border: 1px solid var(--line);
    border-radius: var(--radius);
    padding: 0.4rem 0.9rem;
    color: var(--ink);
  }

  .logout:hover {
    border-color: var(--ink-dim);
  }

  .body {
    flex: 1;
    display: grid;
    grid-template-columns: 15rem 1fr;
  }

  aside {
    border-right: 1px solid var(--line);
    background: var(--surface);
  }

  main {
    padding: 2rem 2.5rem 4rem;
    max-width: 56rem;
  }
</style>
