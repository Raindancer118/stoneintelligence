<script lang="ts">
  import { onMount } from "svelte";
  import type { User } from "oidc-client-ts";
  import { completeLogin, getUser, login as startLogin, logout as startLogout, preferredUsername } from "./lib/auth";
  import { api, type Note, type Vault } from "./lib/api";
  import Sidebar from "./lib/components/Sidebar.svelte";
  import InviteLanding from "./lib/components/InviteLanding.svelte";
  import ObsidianSetup from "./lib/components/ObsidianSetup.svelte";
  import PrivacyPolicy from "./lib/components/PrivacyPolicy.svelte";

  import Icon from "./lib/components/Icon.svelte";
  import QuickJump from "./lib/components/QuickJump.svelte";
  import { connectionConfigJson } from "./lib/connectionConfig";
  let collapsed = $state(false);
  let mobileOpen = $state(false);
  let jumping = $state(false);
  let loadedNotes = $state<Note[]>([]);
  let jumpNote = $state<Note | null>(null);
  let copied = $state(false);
  let canManage = $state(false);
  const areas = $derived([
    { id: "notes", label: "Notizen" }, { id: "obsidian", label: "In Obsidian" },
    { id: "ai", label: "KI-Wissen" }, ...(canManage ? [{ id: "manage", label: "Mitglieder & Rechte" }] : []),
  ]);
  function toggleNavigation() {
    collapsed = !collapsed;
    try { localStorage.setItem("stone.navigation.collapsed", String(collapsed)); } catch { /* Private browsing can disable storage. */ }
  }
  function keyboard(event: KeyboardEvent) {
    if (user && (event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "k") { event.preventDefault(); jumping = !jumping; }
  }
  function jumpToNote(note: Note) {
    if (!mayLeave()) return;
    dirty = false; section = "notes"; setupPage = false; jumpNote = note; jumping = false; mobileOpen = false;
  }
  async function copyConfig() {
    if (!selected) return;
    try { await navigator.clipboard.writeText(connectionConfigJson(selected.id)); copied = true; }
    catch { error = "Die Zwischenablage ist nicht erreichbar. Bitte erlaube den Zugriff und versuche es erneut."; }
  }
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

  let authBusy = $state(false);
  let inviteToken = $state<string | null>(null);
  let setupPage = $state(false);
  /** Datenschutzerklaerung - ohne Anmeldung erreichbar (DSGVO Art. 13). */
  let privacyPage = $state(false);
  const IMPRINT_URL = "https://tstieh.de/impressum";
  function openPrivacy(event?: MouseEvent) {
    event?.preventDefault();
    if (!privacyPage && !mayLeave()) return;
    window.history.pushState({}, "", "/datenschutz");
    privacyPage = true;
  }
  function leavePrivacy(event?: MouseEvent) {
    event?.preventDefault();
    window.history.pushState({}, "", "/");
    privacyPage = false;
  }

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
    selected = vault; loadedNotes = []; jumpNote = null; mobileOpen = false; section = "notes"; dirty = false; canManage = false;
  }
  function navigate(next: "notes" | "manage" | "obsidian" | "ai") { if ((next !== section || setupPage) && mayLeave()) { section = next; setupPage = false; dirty = false; jumpNote = null; } mobileOpen = false; jumping = false; }
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
    try { collapsed = localStorage.getItem("stone.navigation.collapsed") === "true"; } catch { /* Storage is optional. */ }
    void (async () => {
      try {
        if (window.location.pathname === "/callback") {
          const completed = await completeLogin();
          const returnTo = safeReturnPath((completed?.state as { returnTo?: unknown } | undefined)?.returnTo);
          window.history.replaceState({}, "", returnTo ?? "/");
        }
        inviteToken = inviteTokenFrom(window.location.pathname);
        setupPage = window.location.pathname === "/setup";
        privacyPage = window.location.pathname === "/datenschutz";
        const signedIn = await getUser();
        if (!alive) return;
        user = signedIn;
        if (user && !inviteToken) await refreshVaults();
      } catch (e) { if (alive) error = e instanceof Error ? e.message : "Das Dashboard konnte nicht geladen werden."; }
      finally { if (alive) loading = false; }
    })();
    const onPopState = () => { if (!mayLeave()) { window.history.pushState({}, "", setupPage ? "/setup" : privacyPage ? "/datenschutz" : "/"); return; } inviteToken = inviteTokenFrom(window.location.pathname); setupPage = window.location.pathname === "/setup"; privacyPage = window.location.pathname === "/datenschutz"; };
    window.addEventListener("popstate", onPopState);
    return () => { alive = false; window.removeEventListener("popstate", onPopState); };
  });
</script>

<svelte:head><title>{selected ? `${selected.name} · ` : ""}StoneIntelligence</title><meta name="description" content="Deine Notizen lesen, bearbeiten und gemeinsam organisieren." /></svelte:head>
<svelte:window onkeydown={keyboard} />
<a class="skip-link" href="#workspace">Zum Inhalt</a>
{#if loading}
  <main id="workspace" class="boot" role="status"><img src="/logo.png" alt="" width="48" height="48" /><p>Dein Arbeitsplatz wird geladen…</p></main>
{:else if privacyPage}
  <main id="workspace" class="standalone"><a class="gate-brand" href="/" onclick={leavePrivacy}><img src="/logo.png" alt="" width="40" height="40" /><span>StoneIntelligence</span></a><PrivacyPolicy onBack={leavePrivacy} /></main>
{:else if inviteToken}
  <InviteLanding token={inviteToken} signedIn={user !== null} onLogin={() => authenticate()} onJoined={joined} />
{:else if setupPage && !user}
  <main id="workspace" class="standalone"><a class="gate-brand" href="/" onclick={leaveSetup}><img src="/logo.png" alt="" width="40" height="40" /><span>StoneIntelligence</span></a><ObsidianSetup vaults={[]} signedIn={false} onLogin={() => authenticate()} /></main>
{:else if !user}
  <main id="workspace" class="gate">
    <div class="gate-story"><a class="gate-brand" href="/"><img src="/logo.png" alt="" width="42" height="42" /><span>StoneIntelligence</span></a><p class="eyebrow">Für Gedanken, die weitergehen.</p><h1>Wissen wächst.<br />Gemeinsam.</h1><p class="gate-lead">Deine Notizen. Eure Ideen. Ein gemeinsamer Ort – in Obsidian und hier im Browser.</p><div class="gate-illustration" aria-hidden="true"><span class="paper paper-back">Ideen & Verbindungen</span><div class="paper"><span>GEMEINSAMES WISSEN</span><h2>Was wir heute<br />verstanden haben.</h2><i></i><i></i><i></i><p>Festhalten. Weiterdenken.</p></div></div></div>
    <section class="gate-login"><p class="eyebrow">Dein Arbeitsplatz</p><h2>Schön, dass du da bist.</h2><p>Lies, schreibe und teile die Notizen deiner Vaults. Melde dich mit deinem bestehenden Konto an.</p>{#if error}<p class="feedback error" role="alert">{error}</p>{/if}<button class="primary" disabled={authBusy} onclick={() => authenticate()}>{authBusy ? "Anmeldung wird geöffnet…" : "Mit Authentik anmelden"}<Icon name="arrow" /></button><p class="hint">Die Anmeldung läuft sicher über Authentik.</p><div class="gate-setup"><span>Lieber direkt in Obsidian?</span><a href="/setup" onclick={openSetup}>Obsidian einrichten <span aria-hidden="true">↗</span></a></div></section>
  </main>
{:else}
  <div class="shell" class:nav-collapsed={collapsed}>
    <aside class="main-navigation" class:mobile-open={mobileOpen} aria-label="Hauptnavigation">
      <div class="brand"><img src="/logo.png" alt="" width="34" height="34" /><span>Stone<span class="brand-light">Intelligence</span></span></div>
      <button class="nav-collapse" aria-label={collapsed ? "Navigation ausklappen" : "Navigation einklappen"} aria-expanded={!collapsed} onclick={toggleNavigation}><Icon name="panel" /><span>Navigation einklappen</span></button>
      <div class="vault-switcher"><Sidebar {vaults} selectedId={selected?.id ?? null} onSelect={choose} onCreated={refreshVaults} canCreate={() => mayLeave()} /></div>
      {#if selected}<nav class="area-nav" aria-label="Vault-Bereiche"><p class="nav-caption">Arbeitsplatz</p>{#each areas as area}<button class:active={section === area.id && !setupPage} aria-label={area.label} title={area.label} aria-pressed={section === area.id && !setupPage} onclick={() => navigate(area.id as typeof section)}><Icon name={area.id} /><span>{area.label}</span></button>{/each}</nav>{/if}
      <div class="nav-bottom"><a href="/setup" onclick={openSetup}><Icon name="obsidian" /><span>Obsidian einrichten</span></a><div class="account"><span class="avatar">{preferredUsername(user).slice(0, 1).toUpperCase()}</span><span class="account-name">{preferredUsername(user)}<small>Dein Konto</small></span><button class="quiet" disabled={authBusy} onclick={() => authenticate(true)}>Abmelden</button></div></div>
    </aside>
    <div class="main-column">
      <header class="app-header"><button class="mobile-toggle quiet" aria-label={mobileOpen ? "Menü schließen" : "Menü öffnen"} aria-expanded={mobileOpen} onclick={() => mobileOpen = !mobileOpen}><Icon name="panel" /></button><div class="breadcrumb"><span>Dein Wissen</span><span aria-hidden="true">/</span><strong>{setupPage ? "Einrichtung" : selected?.name ?? "Willkommen"}</strong></div><button class="quick-trigger" onclick={() => jumping = true}><Icon name="search" /><span>Schnellsprung</span><kbd>⌘ K</kbd></button></header>
      {#if error}<div class="app-error feedback error" role="alert"><p>{error}</p><button class="secondary" onclick={() => { error = ""; void refreshVaults().catch(e => error = e.message); }}>Erneut versuchen</button></div>{/if}
      <main id="workspace">
        {#if setupPage}<p><a href="/" onclick={leaveSetup}>← Zurück zu den Notizen</a></p><ObsidianSetup {vaults} signedIn={true} onLogin={() => authenticate()} />
        {:else if selected}
          <div class="workspace-heading"><div><p class="eyebrow">{section === "notes" ? "Deine Bibliothek" : section === "ai" ? "Entdecken & verknüpfen" : section === "manage" ? "Gemeinsam arbeiten" : "Überall verbunden"}</p><h1>{selected.name}</h1></div><span class="workspace-badge"><span aria-hidden="true">●</span> {areas.find(a => a.id === section)?.label}</span></div>
          {#key selected.id}
            {#if section === "obsidian"}<ObsidianSetup vaults={[selected]} signedIn={true} scopedVault={true} onLogin={() => authenticate()} /><section class="connection-panel"><h3>Dein eigener Server</h3><p class="hint">Füge die Verbindungsdaten im Plugin unter Erweitert ein.</p><button class="secondary" onclick={copyConfig}>{copied ? "Kopiert" : "Konfiguration kopieren"}</button></section>
            {:else if section === "ai"}{#await import("./lib/components/AiWorkspace.svelte")}<p role="status">KI-Bereich wird geladen…</p>{:then module}<module.default vault={selected} />{:catch}<p class="feedback error" role="alert">Der KI-Bereich konnte nicht geladen werden. <button onclick={() => window.location.reload()}>Erneut versuchen</button></p>{/await}
            {:else if section === "notes"}{#await import("./lib/components/NotesWorkspace.svelte")}<p role="status">Notizbereich wird geladen…</p>{:then module}<module.default vault={selected} requestedNote={jumpNote} onNotesLoaded={notes => loadedNotes = notes} onDirtyChange={value => dirty = value} onPermissions={permissions => canManage = permissions.includes("MANAGE")} />{:catch}<p class="feedback error" role="alert">Der Notizbereich konnte nicht geladen werden. <button onclick={() => window.location.reload()}>Erneut versuchen</button></p>{/await}
            {:else}{#await import("./lib/components/VaultDetail.svelte")}<p role="status">Verwaltung wird geladen…</p>{:then module}<div class="management"><module.default vault={selected} me={preferredUsername(user)} onRenamed={v => { vaults = vaults.map(old => old.id === v.id ? v : old); selected = v; }} onLeft={() => refreshVaults()} /></div>{:catch}<p class="feedback error" role="alert">Die Verwaltung konnte nicht geladen werden. <button onclick={() => window.location.reload()}>Erneut versuchen</button></p>{/await}{/if}
          {/key}
        {:else}<section class="first-vault"><p class="eyebrow">Ein neuer Anfang</p><h1>Hier beginnt dein<br />gemeinsames Wissen.</h1><p>Lege in der Navigation deinen ersten Vault an. Ein Vault bündelt deine Notizen und legt fest, mit wem du sie teilst.</p><button class="primary" onclick={() => { collapsed = false; mobileOpen = true; }}>Ersten Vault anlegen</button></section>{/if}
      </main>
    </div>
  </div>
{/if}
<footer class="legal-links"><span>StoneIntelligence · Raum für Wissen</span><a href="/datenschutz" onclick={openPrivacy}>Datenschutz</a><a href={IMPRINT_URL} rel="noopener">Impressum</a></footer>
{#if jumping && user}<QuickJump notes={loadedNotes} {areas} onNote={jumpToNote} onArea={area => navigate(area as typeof section)} onClose={() => jumping = false} />{/if}
