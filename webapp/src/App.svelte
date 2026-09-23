<script lang="ts">
  import { onMount } from "svelte";
  import type { User } from "oidc-client-ts";
  import { completeLogin, getUser, login as startLogin, logout as startLogout, preferredUsername } from "./lib/auth";
  import { api, type Vault } from "./lib/api";
  import Sidebar from "./lib/components/Sidebar.svelte";
  import InviteLanding from "./lib/components/InviteLanding.svelte";
  import ObsidianSetup from "./lib/components/ObsidianSetup.svelte";

  /** Nur relative App-Pfade als Rücksprungziel - nie eine fremde Adresse aus dem Login-State. */
  function safeReturnPath(value: unknown): string | null {
    return typeof value === "string" && (/^\/invite\/[A-Za-z0-9_-]+$/.test(value) || value === "/setup") ? value : null;
  }
  function inviteTokenFrom(pathname: string): string | null {
    return pathname.match(/^\/invite\/([A-Za-z0-9_-]+)$/)?.[1] ?? null;
  }

  let user = $state<User | null>(null);
  let loading = $state(true);
  let error = $state("");
  let vaults = $state<Vault[]>([]);
  let selected = $state<Vault | null>(null);
  let section = $state<"notes" | "manage" | "obsidian" | "ai">("notes");
  let dirty = $state(false);
  let canManage = $state(false);
  let authBusy = $state(false);
  let inviteToken = $state<string | null>(null);
  let setupPage = $state(false);

  /** Einrichtungsseite ohne Neuladen öffnen/verlassen - der Zurück-Knopf des Browsers funktioniert trotzdem. */
  function openSetup(event?: MouseEvent) {
    event?.preventDefault();
    if (!setupPage && !mayLeave()) return;
    window.history.pushState({}, "", "/setup");
    setupPage = true;
  }
  function leaveSetup(event?: MouseEvent) {
    event?.preventDefault();
    window.history.pushState({}, "", "/");
    setupPage = false;
  }
  let alive = true;

  function mayLeave() { return !dirty || window.confirm("Ungespeicherte Änderungen verwerfen? Speichere oder exportiere deinen Entwurf, wenn du ihn behalten möchtest."); }
  function choose(vault: Vault) {
    if (selected?.id === vault.id || !mayLeave()) return;
    selected = vault; section = "notes"; dirty = false; canManage = false;
  }
  function navigate(next: "notes" | "manage" | "obsidian" | "ai") { if (next !== section && mayLeave()) { section = next; dirty = false; } }
  async function refreshVaults(created?: Vault) {
    const loaded = await api.listVaults();
    if (!alive) return;
    vaults = loaded;
    if (created) { selected = created; section = "notes"; dirty = false; canManage = false; }
    else selected = loaded.find(v => v.id === selected?.id) ?? loaded[0] ?? null;
  }
  async function joined(vaultId: string) {
    inviteToken = null;
    window.history.replaceState({}, "", "/");
    await refreshVaults();
    selected = vaults.find(v => v.id === vaultId) ?? selected;
    // Wer gerade eingeladen wurde, will als Nächstes meist in Obsidian mitarbeiten.
    section = "obsidian";
  }
  async function authenticate(logout = false) {
    if (authBusy || (logout && !mayLeave())) return;
    authBusy = true; error = "";
    try { if (logout) await startLogout(); else await startLogin(inviteToken ? `/invite/${inviteToken}` : setupPage ? "/setup" : undefined); }
    catch (e) { error = e instanceof Error ? e.message : "Die Anmeldung ist gerade nicht erreichbar."; }
    finally { authBusy = false; }
  }
  onMount(() => {
    void (async () => {
      try {
        if (window.location.pathname === "/callback") {
          const completed = await completeLogin();
          const returnTo = safeReturnPath((completed?.state as { returnTo?: unknown } | undefined)?.returnTo);
          window.history.replaceState({}, "", returnTo ?? "/");
        }
        inviteToken = inviteTokenFrom(window.location.pathname);
        setupPage = window.location.pathname === "/setup";
        const signedIn = await getUser();
        if (!alive) return;
        user = signedIn;
        if (user && !inviteToken) await refreshVaults();
      } catch (e) { if (alive) error = e instanceof Error ? e.message : "Das Dashboard konnte nicht geladen werden."; }
      finally { if (alive) loading = false; }
    })();
    const onPopState = () => { setupPage = window.location.pathname === "/setup"; };
    window.addEventListener("popstate", onPopState);
    return () => { alive = false; window.removeEventListener("popstate", onPopState); };
  });
</script>

<svelte:head><title>{selected ? `${selected.name} · ` : ""}StoneIntelligence</title><meta name="description" content="Deine Notizen lesen, bearbeiten und gemeinsam organisieren." /></svelte:head>
{#if loading}<div class="boot" role="status"><img src="/logo.png" alt="" width="48" height="48" /><p>Dein Arbeitsplatz wird geladen…</p></div>
{:else if inviteToken}
  <InviteLanding token={inviteToken} signedIn={user !== null} onLogin={() => authenticate()} onJoined={joined} />
{:else if setupPage && !user}
  <main class="standalone"><a class="gate-brand home" href="/" onclick={leaveSetup}><img src="/logo.png" alt="" width="40" height="40" /><span>StoneIntelligence</span></a><ObsidianSetup vaults={[]} signedIn={false} onLogin={() => authenticate()} /></main>
{:else if !user}
  <main class="gate"><div class="gate-brand"><img src="/logo.png" alt="" width="48" height="48" /><span>StoneIntelligence</span></div><h1>Ein Platz für dein Wissen.</h1><p>Lies und bearbeite deine Obsidian-Notizen im Browser. Deine Vaults und ihre Zugriffsrechte bleiben an einem Ort.</p>{#if error}<p class="feedback error" role="alert">{error}</p>{/if}<button class="primary" disabled={authBusy} onclick={() => authenticate()}>{authBusy ? "Anmeldung wird geöffnet…" : "Mit Authentik anmelden"}</button><p class="hint">Melde dich mit deinem bestehenden Konto an. Du willst in Obsidian arbeiten? <a href="/setup" onclick={openSetup}>Obsidian einrichten</a></p></main>
{:else}
  <div class="shell">
    <header class="app-header"><div class="brand"><img src="/logo.png" alt="" width="30" height="30" /><span>StoneIntelligence</span></div><div class="account"><a class="setup-link" href="/setup" onclick={openSetup}>Obsidian einrichten</a><span>{preferredUsername(user)}</span><button class="quiet" disabled={authBusy} onclick={() => authenticate(true)}>Abmelden</button></div></header>
    {#if error}<div class="app-error feedback error" role="alert"><p>{error}</p><button class="secondary" onclick={() => { error = ""; void refreshVaults().catch(e => error = e.message); }}>Erneut versuchen</button></div>{/if}
    <div class="body"><aside><Sidebar {vaults} selectedId={selected?.id ?? null} onSelect={choose} onCreated={refreshVaults} canCreate={() => mayLeave()} /></aside>
      <main id="workspace">
        {#if setupPage}
          <p><a href="/" onclick={leaveSetup}>← Zurück zu den Notizen</a></p>
          <ObsidianSetup {vaults} signedIn={true} onLogin={() => authenticate()} />
        {:else if selected}
          <div class="workspace-heading"><div><p class="workspace-label">Arbeitsbereich</p><h1>{selected.name}</h1></div><nav aria-label="Vault-Bereiche"><button class:active={section === "notes"} aria-pressed={section === "notes"} onclick={() => navigate("notes")}>Notizen</button><button class:active={section === "obsidian"} aria-pressed={section === "obsidian"} onclick={() => navigate("obsidian")}>In Obsidian</button><button class:active={section === "ai"} aria-pressed={section === "ai"} onclick={() => navigate("ai")}>KI-Wissen</button>{#if canManage}<button class:active={section === "manage"} aria-pressed={section === "manage"} onclick={() => navigate("manage")}>Mitglieder & Rechte</button>{/if}</nav></div>
          {#key selected.id}
            {#if section === "obsidian"}
              <ObsidianSetup vaults={[selected]} signedIn={true} scopedVault={true} onLogin={() => authenticate()} />
            {:else if section === "ai"}
              {#await import("./lib/components/AiWorkspace.svelte")}<p role="status">KI-Bereich wird geladen…</p>{:then module}<module.default vault={selected} />{:catch}<p class="feedback error" role="alert">Der KI-Bereich konnte nicht geladen werden. Bitte lade die Seite erneut.</p>{/await}
            {:else if section === "notes"}
              {#await import("./lib/components/NotesWorkspace.svelte")}<p role="status">Notizbereich wird geladen…</p>{:then module}<module.default vault={selected} onDirtyChange={value => dirty = value} onPermissions={permissions => canManage = permissions.includes("MANAGE")} />{:catch}<p class="feedback error" role="alert">Der Notizbereich konnte nicht geladen werden. Bitte lade die Seite erneut.</p>{/await}
            {:else}
              {#await import("./lib/components/VaultDetail.svelte")}<p role="status">Verwaltung wird geladen…</p>{:then module}<div class="management"><module.default vault={selected} /></div>{:catch}<p class="feedback error" role="alert">Die Verwaltung konnte nicht geladen werden. Bitte lade die Seite erneut.</p>{/await}
            {/if}
          {/key}
        {:else}<section class="first-vault"><h1>Willkommen in deinem Arbeitsplatz.</h1><p>Lege links deinen ersten Vault an. Ein Vault bündelt deine Notizen und legt fest, mit wem du sie teilst.</p><p class="hint">Du kannst anschließend neue Notizen schreiben oder deinen Obsidian-Vault über die Verwaltung verbinden.</p></section>{/if}
      </main>
    </div>
  </div>
{/if}
<style>
  .boot { min-height: 70vh; display: grid; place-content: center; justify-items: center; color: var(--ink-dim); }
  .gate { max-width: 42rem; margin: clamp(4rem, 13vh, 10rem) auto; padding: 2rem; } .gate-brand { display: flex; gap: .8rem; align-items: center; font-weight: 700; margin-bottom: 4rem; } .gate h1 { font-size: clamp(2.4rem, 6vw, 3.8rem); letter-spacing: -.04em; line-height: 1.1; max-width: 14ch; } .gate > p { max-width: 47ch; color: var(--ink-dim); margin: 1.5rem 0; } .gate .primary { padding: .85rem 1.5rem; }
  .app-header { min-height: 76px; display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 1rem; padding: .75rem 2rem; background: var(--surface); border-bottom: 1px solid var(--line); }
  .brand, .account { display: flex; align-items: center; gap: .7rem; } .brand { font-weight: 700; letter-spacing: -.02em; } .account { color: var(--ink-dim); font-size: .85rem; }
  .body { display: grid; grid-template-columns: 14rem minmax(0, 1fr); min-height: calc(100vh - 76px); } aside { background: var(--surface); border-right: 1px solid var(--line); min-width: 0; }
  #workspace { min-width: 0; padding: 2rem clamp(1rem, 3vw, 3rem) 4rem; } .workspace-heading { display: flex; justify-content: space-between; align-items: end; flex-wrap: wrap; gap: 1rem; margin-bottom: 2rem; } .workspace-heading > * { min-width: 0; max-width: 100%; } .workspace-heading h1 { font-size: 1.9rem; margin: .25rem 0 0; overflow-wrap: anywhere; } .workspace-label { color: var(--ink-dim); font-size: .8rem; margin: 0; }
  /* Auf schmalen Bildschirmen scrollt die Reiterleiste für sich, statt die Seite zu verbreitern. */
  nav { display: flex; gap: .4rem; border-bottom: 1px solid var(--line); max-width: 100%; min-width: 0; overflow-x: auto; scrollbar-width: none; } nav button { flex: none; white-space: nowrap; border: 0; border-bottom: 2px solid transparent; background: none; color: var(--ink-dim); padding: .6rem 1rem; } nav button.active { color: var(--forest); border-color: var(--forest); font-weight: 700; }
  .standalone { max-width: 50rem; margin: clamp(2.5rem, 8vh, 6rem) auto; padding: 2rem; } .home { color: var(--ink); text-decoration: none; margin-bottom: 3rem; display: inline-flex; }
  .setup-link { color: var(--forest); font-weight: 500; text-decoration: none; min-height: 48px; display: inline-flex; align-items: center; } .setup-link:hover { text-decoration: underline; }
  .management { max-width: 62rem; } .app-error { margin: 1rem 2rem; } .first-vault { padding: 4rem 0; max-width: 44rem; } .first-vault h1 { font-size: 2.5rem; } .first-vault p { max-width: 58ch; color: var(--ink-dim); }
  @media (max-width: 1100px) { .body { grid-template-columns: 11.5rem minmax(0, 1fr); } }
  @media (max-width: 850px) { .body { display: block; } aside { border-right: 0; border-bottom: 1px solid var(--line); } #workspace { padding-top: 1.5rem; } .app-header { padding: .6rem 1rem; } }
</style>
