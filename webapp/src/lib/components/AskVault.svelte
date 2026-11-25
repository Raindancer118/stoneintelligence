<script lang="ts">
  import Skeleton from "./Skeleton.svelte";
  import { onDestroy, onMount } from "svelte";
  import { api, type AiService, type AskQuestion, type Vault } from "../api";
  import { badge, badgeClass, isOpen, omittedText, renderAnswer, statusText } from "../askText";

  // "Frag den Vault" (ADR 0017) - dieselben Funktionen wie der Befehl in Obsidian.
  let { vault, onOpenNote }: { vault: Vault; onOpenNote: (noteId: string, path: string) => void } = $props();

  const MIN_LENGTH = 5;
  const MAX_LENGTH = 1000;
  const POLL_MS = 2000;

  let loading = $state(true);
  let error = $state<string | null>(null);
  let services = $state<AiService[]>([]);
  let serviceId = $state("");
  let history = $state<AskQuestion[]>([]);
  let openId = $state<string | null>(null);
  let text = $state("");
  let asking = $state(false);
  let consent = $state<boolean | null>(null);
  let consentBusy = $state(false);
  let timer: ReturnType<typeof setTimeout> | null = null;
  let alive = true;

  const service = $derived(services.find(s => s.id === serviceId) ?? null);
  const external = $derived(service !== null && service.local !== true);
  const canAsk = $derived(text.trim().length >= MIN_LENGTH && !asking);

  function when(iso: string) { return new Date(iso).toLocaleString("de-DE", { dateStyle: "medium", timeStyle: "short" }); }
  function serviceName(id: string) { return services.find(s => s.id === id)?.name ?? id; }

  async function load() {
    loading = true;
    try {
      [services, history] = await Promise.all([api.aiServices(), api.askHistory(vault.id)]);
      if (!serviceId && services.length) serviceId = (services.find(s => s.local) ?? services[0]).id;
      if (history.length && openId === null) openId = history[0].id;
      error = null;
    } catch (e) {
      error = (e as Error).message;
    } finally {
      loading = false;
      schedulePoll();
    }
    try { consent = Boolean((await api.linkingSettings(vault.id)).aiConsent); } catch { consent = null; }
  }

  /** Solange eine Frage wartet oder läuft, alle paar Sekunden nachsehen - danach Ruhe. */
  function schedulePoll() {
    if (timer) clearTimeout(timer);
    timer = null;
    if (!alive || !history.some(isOpen)) return;
    timer = setTimeout(async () => {
      try { history = await api.askHistory(vault.id); } catch { /* nächster Takt versucht es erneut */ }
      schedulePoll();
    }, POLL_MS);
  }

  onMount(() => { void load(); });
  onDestroy(() => { alive = false; if (timer) clearTimeout(timer); });

  async function submit() {
    if (!canAsk) return;
    asking = true; error = null;
    try {
      const created = await api.askVault(vault.id, text.trim(), services.length > 1 ? serviceId : null);
      history = [created, ...history.filter(q => q.id !== created.id)];
      openId = created.id;
      text = "";
      schedulePoll();
    } catch (e) {
      error = (e as Error).message;
    } finally {
      asking = false;
    }
  }

  async function cancel(question: AskQuestion) {
    try {
      const updated = await api.cancelAsk(vault.id, question.id);
      history = history.map(q => q.id === updated.id ? updated : q);
      error = null;
    } catch (e) { error = (e as Error).message; }
  }

  async function remove(question: AskQuestion) {
    if (!window.confirm("Diese Frage samt Antwort löschen?")) return;
    try {
      await api.deleteAsk(vault.id, question.id);
      history = history.filter(q => q.id !== question.id);
      error = null;
    } catch (e) { error = (e as Error).message; }
  }

  async function setConsent(value: boolean) {
    if (consentBusy) return;
    consentBusy = true;
    try { consent = Boolean((await api.setLinkingConsent(vault.id, value)).aiConsent); error = null; }
    catch (e) { error = (e as Error).message; }
    finally { consentBusy = false; }
  }

  /** Ein Klick auf ein Zitat im Antworttext öffnet die Notiz; Links ohne Zitat gibt es dort nicht. */
  function answerClick(event: MouseEvent, question: AskQuestion) {
    const link = (event.target as Element).closest<HTMLAnchorElement>('a[href^="#cite-"]');
    if (!link) return;
    event.preventDefault();
    const citation = question.citations.find(c => `#cite-${c.n}` === link.getAttribute("href"));
    if (citation) onOpenNote(citation.noteId, citation.path);
  }
</script>

<section class="ask">
  <header class="intro">
    <h2>Frag den Vault</h2>
    <p>Stell eine Frage in eigenen Worten. Die Antwort entsteht nur aus Notizen, die du lesen darfst, und nennt ihre Quellen. Was die Notizen nicht hergeben, steht dort auch nicht.</p>
  </header>

  {#if error}<div class="feedback error" role="alert"><p>{error}</p></div>{/if}

  {#if loading}
    <Skeleton kind="panel" label="Frag den Vault wird geladen…" />
  {:else if services.length === 0}
    <p class="feedback">Auf diesem Server ist noch keine KI eingerichtet. Sobald der Betrieb einen KI-Dienst anbindet, kannst du hier Fragen an deinen Vault stellen.</p>
  {:else}
    <form class="upload ask-form" onsubmit={(event) => { event.preventDefault(); void submit(); }}>
      <label>Deine Frage
        <textarea rows="3" maxlength={MAX_LENGTH} placeholder="z. B. Welche Meilensteine nennt der Projektplan?" bind:value={text}></textarea>
      </label>
      {#if services.length > 1}
        <label>KI-Dienst
          <select bind:value={serviceId}>
            {#each services as option (option.id)}<option value={option.id}>{option.name}</option>{/each}
          </select>
        </label>
      {/if}
      {#if service}
        <p class="hint">
          {#if external}Passende Textstellen werden an „{service.name}“ übertragen, aber nur aus Notizen, deren Verfasser einer Auswertung durch externe KI-Anbieter zugestimmt haben (siehe <a href="/datenschutz">Datenschutz</a>).
          {:else}„{service.name}“ läuft auf eigener Infrastruktur, nichts verlässt den Server.{/if}
        </p>
      {/if}
      {#if external && consent !== null}
        <label class="toggle"><input type="checkbox" checked={consent} disabled={consentBusy} onchange={e => setConsent(e.currentTarget.checked)} /> Meine Notizen dürfen an externe KI-Anbieter (jederzeit widerrufbar)</label>
      {/if}
      <div class="ask-actions">
        <button class="primary" type="submit" disabled={!canAsk}>{asking ? "Wird gestellt…" : "Fragen"}</button>
      </div>
    </form>

    <section class="block" aria-label="Verlauf der Fragen">
      <h3>Deine Fragen</h3>
      {#if history.length === 0}
        <p class="hint">Du hast noch nichts gefragt. Fragen und Antworten siehst nur du.</p>
      {:else}
        <ol class="questions">
          {#each history as item (item.id)}
            <li class="question">
              <div class="question-line">
                <button class="question-head" aria-expanded={openId === item.id} onclick={() => openId = openId === item.id ? null : item.id}>
                  <span class="name">{item.question}</span>
                  <span class="meta">{serviceName(item.service)} · {when(item.askedAt)}</span>
                </button>
                <span class="status {badgeClass(item)}">{badge(item)}</span>
              </div>
              {#if openId === item.id}
                <div class="answer">
                  {#if isOpen(item)}
                    <p class="detail" role="status">{statusText(item)}{#if item.status === "PENDING" && item.waitingForCapacity && item.availableAt} · startet von selbst ab {when(item.availableAt)}{/if}</p>
                    {#if item.status === "RUNNING" && item.percent !== null}<progress class="job-progress" value={item.percent} max="100" aria-label="Fortschritt"></progress>{/if}
                    <button class="quiet" onclick={() => cancel(item)}>Abbrechen</button>
                  {:else}
                    {#if item.status === "ANSWERED" && item.answer}
                      <!-- eslint-disable-next-line svelte/no-at-html-tags -- gesäubert durch renderAnswer (DOMPurify), nur Zitat-Anker bleiben als Links -->
                      <div class="markdown" role="presentation" onclick={(event) => answerClick(event, item)}>{@html renderAnswer(item.answer)}</div>
                      {#if item.citations.length}
                        <ul class="sources" aria-label="Quellen">
                          {#each item.citations as citation (citation.n)}
                            <li>
                              <button class="source" onclick={() => onOpenNote(citation.noteId, citation.path)} aria-label={`Quelle ${citation.n}: ${citation.path}`}>
                                <span class="number">[{citation.n}]</span>
                                <span class="where"><span class="path">{citation.path}</span>{#if citation.heading && !citation.path.endsWith(`${citation.heading}.md`)}<span class="heading"> · {citation.heading}</span>{/if}</span>
                              </button>
                              <span class="excerpt">{citation.excerpt}</span>
                            </li>
                          {/each}
                        </ul>
                      {/if}
                    {:else if item.restricted}
                      <p class="feedback">Diese Antwort ist nicht mehr sichtbar, weil du eine der zitierten Notizen nicht mehr lesen darfst.</p>
                    {:else if item.status === "NOT_ANSWERABLE"}
                      <p class="feedback">Die Notizen, die du lesen darfst, beantworten diese Frage nicht.</p>
                    {:else if item.status === "FAILED"}
                      <p class="detail problem">{statusText(item)}</p>
                    {:else if item.status === "CANCELLED"}
                      <p class="detail">Abgebrochen.</p>
                    {/if}
                    {#if omittedText(item.omittedForConsent)}<p class="hint" role="note">{omittedText(item.omittedForConsent)}</p>{/if}
                    <button class="quiet" onclick={() => remove(item)}>Löschen</button>
                  {/if}
                </div>
              {/if}
            </li>
          {/each}
        </ol>
      {/if}
    </section>
  {/if}
</section>
