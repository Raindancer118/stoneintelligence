<script lang="ts">
  import { onMount } from "svelte";
  import { api, type InvitationInfo } from "../api";

  let { token, signedIn, onLogin, onJoined }: {
    token: string; signedIn: boolean; onLogin: () => void; onJoined: (vaultId: string) => void;
  } = $props();

  let info = $state<InvitationInfo | null>(null);
  let error = $state("");
  let accepting = $state(false);

  const closedText: Record<string, string> = {
    EXPIRED: "Diese Einladung ist abgelaufen. Bitte die Person, die dich eingeladen hat, um eine neue.",
    ACCEPTED: "Diese Einladung wurde bereits angenommen. Melde dich an, um den Vault zu öffnen.",
    REVOKED: "Diese Einladung wurde zurückgezogen.",
  };
  const dateFormat = new Intl.DateTimeFormat("de-DE", { day: "numeric", month: "long", year: "numeric" });

  onMount(() => {
    void api.describeInvitation(token).then(loaded => info = loaded, (e: Error) => error = e.message);
  });

  async function accept() {
    accepting = true; error = "";
    try { onJoined((await api.acceptInvitation(token)).vaultId); }
    catch (e) { error = (e as Error).message; }
    finally { accepting = false; }
  }
</script>

<main class="invite-landing">
  <div class="brand"><img src="/logo.png" alt="" width="40" height="40" /><span>StoneIntelligence</span></div>
  {#if error && !info}
    <h1>Einladung nicht gefunden</h1>
    <p class="error" role="alert">{error}</p>
  {:else if !info}
    <p role="status">Einladung wird geladen…</p>
  {:else}
    <h1>{info.invitedBy} lädt dich zu „{info.vaultName}“ ein</h1>
    {#if info.state !== "PENDING"}
      <p class="lead">{closedText[info.state]}</p>
      {#if !signedIn && info.state === "ACCEPTED"}<button class="primary" onclick={onLogin}>Anmelden</button>{/if}
    {:else}
      <p class="lead">Du kannst die Notizen dort {info.access === "READ" ? "lesen" : "lesen und bearbeiten"} – im Browser und in Obsidian. Die Einladung ging an {info.maskedEmail} und gilt bis zum {dateFormat.format(new Date(info.expiresAt))}.</p>
      {#if signedIn}
        <button class="primary" disabled={accepting} onclick={accept}>{accepting ? "Wird angenommen…" : "Einladung annehmen"}</button>
      {:else}
        <div class="choices">
          {#if info.enrollmentUrl}
            <div>
              <a class="primary button-link" href={info.enrollmentUrl} target="_blank" rel="noopener noreferrer">Neues Konto erstellen</a>
              <p class="hint">Öffnet sich in einem neuen Tab. Danach hier anmelden und die Einladung annehmen.</p>
            </div>
          {/if}
          <div>
            <button class={info.enrollmentUrl ? "secondary" : "primary"} onclick={onLogin}>Ich habe schon ein Konto – anmelden</button>
          </div>
        </div>
      {/if}
      {#if error}<p class="error" role="alert">{error}</p>{/if}
    {/if}
  {/if}
</main>

<style>
  .invite-landing { max-width: 42rem; margin: clamp(3rem, 12vh, 8rem) auto; padding: 2rem; }
  .brand { display: flex; gap: .7rem; align-items: center; font-weight: 700; margin-bottom: 3rem; }
  h1 { font-size: clamp(1.9rem, 5vw, 2.8rem); letter-spacing: -.03em; line-height: 1.15; overflow-wrap: anywhere; }
  .lead { color: var(--ink-dim); max-width: 52ch; margin: 1.25rem 0 2rem; }
  .choices { display: grid; gap: 1.25rem; }
  .choices .hint { margin: .5rem 0 0; }
  .button-link { display: inline-flex; align-items: center; min-height: 48px; text-decoration: none; }
</style>
