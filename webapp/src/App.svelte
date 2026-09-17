<script lang="ts">
  import { onMount } from "svelte";
  import type { User } from "oidc-client-ts";
  import { completeLogin, getUser, login as startLogin, logout as startLogout, preferredUsername } from "./lib/auth";
  import Dashboard from "./lib/components/Dashboard.svelte";

  let user = $state<User | null>(null);
  let loading = $state(true);
  let error = $state<string | null>(null);

  onMount(async () => {
    try {
      if (window.location.pathname === "/callback") {
        await completeLogin();
        window.history.replaceState({}, "", "/");
      }
      user = await getUser();
    } catch (e) {
      error = (e as Error).message;
    } finally {
      loading = false;
    }
  });
</script>

<header>
  <span class="brand mono">stoneintelligence<span class="brand-accent">/kb</span></span>
  {#if user}
    <button class="ghost" onclick={() => startLogout()}>Abmelden</button>
  {/if}
</header>

<main>
  {#if loading}
    <p class="dim">Laden…</p>
  {:else if error}
    <p class="error">{error}</p>
  {:else if !user}
    <section class="gate">
      <h1>Willkommen.</h1>
      <p class="dim">Anmeldung über Authentik, um deine Vaults zu verwalten.</p>
      <button class="primary" onclick={() => startLogin()}>Mit Authentik anmelden</button>
    </section>
  {:else}
    <h1>Willkommen, {preferredUsername(user)}.</h1>
    <Dashboard />
  {/if}
</main>

<style>
  header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 1rem 2rem;
    border-bottom: 1px solid var(--ink-line);
    background: var(--ink-raised);
  }

  .brand {
    font-size: 0.95rem;
    letter-spacing: 0.02em;
  }

  .brand-accent {
    color: var(--ember);
  }

  main {
    max-width: 960px;
    margin: 0 auto;
    padding: 3rem 2rem 6rem;
  }

  .gate {
    margin-top: 4rem;
    text-align: center;
  }

  .dim {
    color: var(--paper-dim);
  }

  .error {
    color: var(--rust);
  }

  button.primary {
    margin-top: 1.5rem;
    background: var(--ember);
    color: var(--ink);
    border: none;
    padding: 0.7rem 1.6rem;
    border-radius: var(--radius);
    font-weight: 600;
  }

  button.primary:hover {
    background: #e88a4f;
  }

  button.ghost {
    background: transparent;
    color: var(--paper-dim);
    border: 1px solid var(--ink-line);
    padding: 0.4rem 0.9rem;
    border-radius: var(--radius);
  }

  button.ghost:hover {
    color: var(--paper);
    border-color: var(--paper-dim);
  }
</style>
