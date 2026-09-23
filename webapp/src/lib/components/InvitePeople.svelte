<script lang="ts">
  import { onMount } from "svelte";
  import { api, type InviteAccess, type PendingInvitation, type PersonSuggestion, type Vault } from "../api";

  let { vault }: { vault: Vault } = $props();

  // Ein Feld für beides: Namen tippen findet bestehende Konten, eine vollständige Adresse lädt
  // per E-Mail ein. So muss niemand vorher wissen, ob die Person schon ein Konto hat.
  const EMAIL = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;

  let query = $state("");
  let access = $state<InviteAccess>("EDIT");
  let suggestions = $state<PersonSuggestion[]>([]);
  let pending = $state<PendingInvitation[]>([]);
  let busy = $state(false);
  let message = $state("");
  let error = $state("");
  let searchTimer: ReturnType<typeof setTimeout> | undefined;
  let searchGeneration = 0;

  const trimmed = $derived(query.trim());
  const looksLikeEmail = $derived(EMAIL.test(trimmed));

  async function loadPending() {
    try { pending = await api.listInvitations(vault.id); }
    catch (e) { error = (e as Error).message; }
  }
  onMount(() => { void loadPending(); return () => clearTimeout(searchTimer); });

  function onInput() {
    message = ""; error = "";
    clearTimeout(searchTimer);
    if (trimmed.length < 2) { suggestions = []; return; }
    const generation = ++searchGeneration;
    searchTimer = setTimeout(async () => {
      try {
        const found = await api.searchPeople(vault.id, trimmed);
        if (generation === searchGeneration) suggestions = found;
      } catch (e) { if (generation === searchGeneration) error = (e as Error).message; }
    }, 250);
  }

  function reset(text: string) {
    message = text; query = ""; suggestions = []; searchGeneration++;
  }

  async function add(person: PersonSuggestion) {
    busy = true; error = "";
    try {
      const result = await api.addPerson(vault.id, person.username, access);
      reset(result.status === "ALREADY_MEMBER" ? `${person.name} ist bereits Mitglied.` : `${person.name} ist jetzt Mitglied.`);
    } catch (e) { error = (e as Error).message; }
    finally { busy = false; }
  }

  async function inviteEmail() {
    const email = trimmed;
    busy = true; error = "";
    try {
      const result = await api.inviteByEmail(vault.id, email, access);
      reset(result.status === "INVITED" ? `Einladung an ${email} verschickt.`
        : result.status === "ADDED" ? `${result.displayName} hatte schon ein Konto und ist jetzt Mitglied.`
        : `${result.displayName} ist bereits Mitglied.`);
      await loadPending();
    } catch (e) { error = (e as Error).message; }
    finally { busy = false; }
  }

  async function revoke(invitation: PendingInvitation) {
    error = "";
    try {
      await api.revokeInvitation(vault.id, invitation.id);
      pending = await api.listInvitations(vault.id);
    } catch (e) { error = (e as Error).message; }
  }

  const dateFormat = new Intl.DateTimeFormat("de-DE", { day: "numeric", month: "long" });
</script>

<section class="invite">
  <h3>Personen einladen</h3>
  <p class="hint">Namen eingeben, um ein bestehendes Konto zu finden – oder eine E-Mail-Adresse, um jemanden ohne Konto einzuladen. Wer eingeladen wird, bearbeitet mit, verwaltet aber keine Mitglieder.</p>

  <form class="invite-form" onsubmit={(event) => { event.preventDefault(); if (looksLikeEmail && !busy) void inviteEmail(); }}>
    <label class="field">
      <span>Name oder E-Mail-Adresse</span>
      <input type="text" autocomplete="off" spellcheck="false" bind:value={query} oninput={onInput} placeholder="z. B. Anna oder anna@beispiel.de" />
    </label>
    <label class="field access">
      <span>Rechte</span>
      <select bind:value={access}>
        <option value="EDIT">Darf bearbeiten</option>
        <option value="READ">Darf nur lesen</option>
      </select>
    </label>
  </form>

  {#if suggestions.length > 0}
    <ul class="people" aria-label="Gefundene Konten">
      {#each suggestions as person (person.username)}
        <li>
          <div><span class="name">{person.name}</span> <span class="meta">{person.maskedEmail}</span></div>
          {#if person.alreadyMember}
            <span class="meta">bereits Mitglied</span>
          {:else}
            <button class="secondary" disabled={busy} aria-label={`${person.name} hinzufügen`} onclick={() => add(person)}>Hinzufügen</button>
          {/if}
        </li>
      {/each}
    </ul>
  {/if}

  {#if looksLikeEmail}
    <div class="email-invite">
      <button class="primary" disabled={busy} onclick={inviteEmail}>{busy ? "Wird gesendet…" : `Einladung an ${trimmed} senden`}</button>
      <p class="hint">Hat die Adresse schon ein Konto, wird die Person direkt hinzugefügt. Sonst bekommt sie einen Link, legt damit ihr Konto an und nimmt die Einladung an.</p>
    </div>
  {:else if trimmed.length >= 2 && suggestions.length === 0}
    <p class="hint">Kein Konto gefunden. Mit der vollständigen E-Mail-Adresse kannst du die Person einladen.</p>
  {/if}

  {#if message}<p class="notice" role="status">{message}</p>{/if}
  {#if error}<p class="error" role="alert">{error}</p>{/if}

  {#if pending.length > 0}
    <h4>Offene Einladungen</h4>
    <ul class="people">
      {#each pending as invitation (invitation.id)}
        <li>
          <div><span class="name">{invitation.email}</span> <span class="meta">{invitation.access === "READ" ? "lesen" : "bearbeiten"} · gültig bis {dateFormat.format(new Date(invitation.expiresAt))}</span></div>
          <button class="quiet" aria-label={`Einladung an ${invitation.email} zurückziehen`} onclick={() => revoke(invitation)}>Zurückziehen</button>
        </li>
      {/each}
    </ul>
  {/if}
</section>

<style>
  .invite-form { display: flex; flex-wrap: wrap; gap: .75rem; align-items: end; margin: 1rem 0 .5rem; }
  .field { display: grid; gap: .35rem; font-size: .8rem; color: var(--ink-dim); }
  .field:first-child { flex: 1 1 18rem; }
  .field input { width: 100%; }
  .people { list-style: none; padding: 0; margin: .5rem 0 1rem; border-top: 1px solid var(--line); }
  .people li { display: flex; justify-content: space-between; align-items: center; gap: 1rem; min-height: 48px; padding: .35rem 0; border-bottom: 1px solid var(--line); }
  .name { font-weight: 500; overflow-wrap: anywhere; }
  .meta { color: var(--ink-dim); font-size: .8rem; }
  .email-invite { margin: .75rem 0; }
  .email-invite .hint { margin: .5rem 0 0; max-width: 60ch; }
  .notice { color: var(--forest); font-weight: 500; }
  h4 { margin: 1.5rem 0 .25rem; font-size: .85rem; letter-spacing: .06em; text-transform: uppercase; color: var(--ink-dim); }
</style>
