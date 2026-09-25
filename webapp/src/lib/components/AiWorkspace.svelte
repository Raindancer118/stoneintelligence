<script lang="ts">
  import { onDestroy, onMount } from "svelte";
  import {
    api, type AiChangeSet, type AiChangeSetDetail, type AiJob, type AiQuota, type AiRevertReport, type AiService,
    type AiServiceCapacity, type Vault,
  } from "../api";

  let { vault }: { vault: Vault } = $props();

  // Dieselben Grenzen wie der Server (AiJobService) - so scheitert nichts erst nach dem Hochladen.
  const MAX_FILE_BYTES = 20 * 1024 * 1024;
  const MAX_FILES = 10;
  const READABLE = /\.(pdf|md|markdown|txt)$/i;
  const POLL_MS = 3000;
  /** Der Worker meldet etwa minütlich - öfter nachsehen bringt nichts Neues. */
  const CAPACITY_POLL_MS = 60_000;
  const PROVIDER_NAMES: Record<string, string> = {
    groq: "Groq", "google-gemini": "Google Gemini", gemini: "Google Gemini", mistral: "Mistral", openrouter: "OpenRouter",
  };

  let loading = $state(true);
  let error = $state<string | null>(null);
  let services = $state<AiService[]>([]);
  let permissions = $state<string[]>([]);
  let jobs = $state<AiJob[]>([]);
  let changeSets = $state<AiChangeSet[]>([]);

  let serviceId = $state("");
  let level = $state(1);
  let files = $state<File[]>([]);
  let rejected = $state<string[]>([]);
  let uploading = $state(false);
  let uploadNotice = $state<string | null>(null);

  let openDetail = $state<string | null>(null);
  let details = $state<Record<string, AiChangeSetDetail>>({});
  let reverting = $state<string | null>(null);
  let report = $state<{ label: string; result: AiRevertReport } | null>(null);

  let capacity = $state<AiServiceCapacity | null>(null);
  let capacityFor = "";
  let capacityTimer: ReturnType<typeof setInterval> | null = null;

  let timer: ReturnType<typeof setTimeout> | null = null;
  let alive = true;

  const service = $derived(services.find(s => s.id === serviceId) ?? null);
  const canUpload = $derived(permissions.includes("CREATE"));
  const canRevert = $derived(permissions.includes("WRITE") && permissions.includes("DELETE"));

  function plural(count: number, one: string, many: string) { return `${count} ${count === 1 ? one : many}`; }
  function when(iso: string) { return new Date(iso).toLocaleString("de-DE", { dateStyle: "medium", timeStyle: "short" }); }
  function serviceName(id: string) { return services.find(s => s.id === id)?.name ?? id; }
  function number(value: number, digits = 0) {
    return value.toLocaleString("de-DE", { minimumFractionDigits: digits, maximumFractionDigits: digits });
  }
  function providerName(id: string) { return PROVIDER_NAMES[id] ?? id; }
  function quota(value: AiQuota, unit: string) {
    const left = value.limit > 0 ? `${number(value.remaining)} von ${number(value.limit)} ${unit}` : `${number(value.remaining)} ${unit} übrig`;
    return value.resetsAt ? `${left} (voll ab ${when(value.resetsAt)})` : left;
  }
  function providerLine(p: AiServiceCapacity["providers"][number]): string {
    if (p.exhausted) {
      return p.availableAgainAt ? `ausgeschöpft, wieder ab ${when(p.availableAgainAt)}` : "ausgeschöpft, unbekannt bis wann";
    }
    const parts = [
      p.requests ? quota(p.requests, "Anfragen") : null,
      p.tokens ? quota(p.tokens, "Tokens") : null,
      p.credits ? (p.credits.limit !== null
        ? `${number(p.credits.remaining, 2)} von ${number(p.credits.limit, 2)} $ Guthaben`
        : `${number(p.credits.remaining, 2)} $ Guthaben`) : null,
    ].filter(Boolean);
    if (p.keys > 1) parts.push(`${p.usableKeys} von ${p.keys} Schlüsseln frei`);
    return parts.length ? parts.join(" · ") : "verfügbar – genaue Mengen meldet der Anbieter mit der nächsten Antwort";
  }

  async function loadCapacity(id: string) {
    if (!id) return;
    capacityFor = id;
    try {
      const result = await api.aiCapacity(id);
      if (capacityFor === id) capacity = result;
    } catch {
      // Die Anzeige ist ein Hinweis; ohne sie lässt sich trotzdem hochladen.
      if (capacityFor === id) capacity = null;
    }
  }

  const STATUS: Record<AiJob["status"], string> = {
    PENDING: "Wartet", RUNNING: "Wird verarbeitet", SUCCEEDED: "Fertig", FAILED: "Fehlgeschlagen", CANCELLED: "Abgebrochen",
  };
  function jobDetail(job: AiJob): string | null {
    if (job.status === "RUNNING") {
      const parts = [job.progress, job.percent !== null ? `${job.percent} %` : null].filter(Boolean);
      return parts.length ? parts.join(" · ") : null;
    }
    if (job.status === "FAILED") return job.error;
    if (job.status === "SUCCEEDED") return job.progress;
    if (job.status === "PENDING" && job.waitingForCapacity) {
      return [job.error, job.availableAt ? `startet von selbst ab ${when(job.availableAt)}` : null].filter(Boolean).join(" · ");
    }
    if (job.status === "PENDING" && job.error) return `Neuer Versuch folgt – ${job.error}`;
    return null;
  }

  async function load() {
    try {
      [services, permissions, jobs, changeSets] = await Promise.all([
        api.aiServices(), api.permissions(vault.id), api.listAiJobs(vault.id), api.listChangeSets(vault.id),
      ]);
      if (!serviceId && services.length) chooseService(services[0].id);
      error = null;
    } catch (e) {
      error = (e as Error).message;
    } finally {
      loading = false;
      schedulePoll();
    }
  }

  /** Solange etwas wartet oder läuft, alle paar Sekunden nachsehen - danach Ruhe. */
  function schedulePoll() {
    if (timer) clearTimeout(timer);
    timer = null;
    if (!alive || !jobs.some(job => job.status === "PENDING" || job.status === "RUNNING")) return;
    timer = setTimeout(async () => {
      const wasOpen = new Set(jobs.filter(j => j.status === "PENDING" || j.status === "RUNNING").map(j => j.id));
      try {
        jobs = await api.listAiJobs(vault.id);
        if (jobs.some(job => wasOpen.has(job.id) && job.status === "SUCCEEDED")) changeSets = await api.listChangeSets(vault.id);
      } catch {
        // Nächster Takt versucht es erneut.
      }
      schedulePoll();
    }, POLL_MS);
  }

  onMount(() => {
    void load();
    capacityTimer = setInterval(() => void loadCapacity(serviceId), CAPACITY_POLL_MS);
  });
  onDestroy(() => {
    alive = false;
    if (timer) clearTimeout(timer);
    if (capacityTimer) clearInterval(capacityTimer);
  });

  function chooseService(id: string) {
    serviceId = id;
    if (id !== capacityFor) { capacity = null; void loadCapacity(id); }
    const allowed = services.find(s => s.id === id)?.levels ?? [];
    if (!allowed.includes(level)) level = allowed[0] ?? 1;
  }

  function choose(list: FileList | null) {
    const chosen = [...(list ?? [])];
    const problems: string[] = [];
    const accepted: File[] = [];
    for (const file of chosen) {
      if (!READABLE.test(file.name)) problems.push(`${file.name}: nur PDF-, Markdown- und Textdateien werden unterstützt`);
      else if (file.size === 0) problems.push(`${file.name} ist leer`);
      else if (file.size > MAX_FILE_BYTES) problems.push(`${file.name} ist größer als 20 MB`);
      else accepted.push(file);
    }
    if (accepted.length > MAX_FILES) {
      problems.push(`Höchstens ${MAX_FILES} Dokumente auf einmal – die ersten ${MAX_FILES} sind ausgewählt.`);
      accepted.length = MAX_FILES;
    }
    files = accepted;
    rejected = problems;
    uploadNotice = null;
  }

  async function upload() {
    if (!service || files.length === 0 || uploading) return;
    uploading = true;
    uploadNotice = null;
    const failed: string[] = [];
    let queued = 0;
    for (const file of files) {
      try {
        await api.uploadAiDocument(vault.id, service.id, level, file);
        queued++;
      } catch (e) {
        failed.push(`${file.name}: ${(e as Error).message}`);
      }
    }
    files = [];
    rejected = failed;
    uploadNotice = queued ? `${plural(queued, "Dokument", "Dokumente")} in der Warteschlange.` : null;
    uploading = false;
    try { jobs = await api.listAiJobs(vault.id); } catch (e) { error = (e as Error).message; }
    schedulePoll();
  }

  async function cancel(job: AiJob) {
    if (job.status === "RUNNING"
      && !window.confirm(`Verarbeitung von „${job.fileName}“ abbrechen? Was die KI daraus schon geschrieben hat, wird rückgängig gemacht.`)) {
      return;
    }
    try {
      await api.cancelAiJob(vault.id, job.id);
      jobs = await api.listAiJobs(vault.id);
    } catch (e) {
      error = (e as Error).message;
    }
  }

  async function toggle(set: AiChangeSet) {
    if (openDetail === set.id) { openDetail = null; return; }
    openDetail = set.id;
    if (!details[set.id]) {
      try { details = { ...details, [set.id]: await api.changeSet(vault.id, set.id) }; }
      catch (e) { error = (e as Error).message; }
    }
  }

  async function revert(set: AiChangeSet) {
    if (reverting) return;
    const confirmed = window.confirm(`Alle Änderungen der KI aus „${set.label}“ rückgängig machen? Notizen, an denen seitdem jemand weitergeschrieben hat, bleiben unverändert.`);
    if (!confirmed) return;
    reverting = set.id;
    try {
      report = { label: set.label, result: await api.revertChangeSet(vault.id, set.id) };
      changeSets = await api.listChangeSets(vault.id);
      error = null;
    } catch (e) {
      error = (e as Error).message;
    } finally {
      reverting = null;
    }
  }
</script>

<section class="ai">
  <header class="intro">
    <h2>Wissen aus Dokumenten</h2>
    <p>Lade Skripte, Artikel oder Mitschriften hoch. Eine KI liest sie und legt daraus verlinkte Notizen mit Quellenangabe an. Deine eigenen Notizen verändert sie nicht, und jede Verarbeitung lässt sich vollständig rückgängig machen.</p>
  </header>

  {#if error}<p class="feedback error" role="alert">{error}</p>{/if}

  {#if loading}
    <p role="status">KI-Bereich wird geladen…</p>
  {:else}
    {#if services.length === 0}
      <p class="feedback">Auf diesem Server ist noch keine KI eingerichtet. Sobald der Betrieb einen KI-Dienst anbindet, kannst du hier Dokumente einlesen lassen.</p>
    {:else if canUpload}
      <form class="upload" onsubmit={(event) => { event.preventDefault(); void upload(); }}>
        <div class="choices">
          <label>KI-Dienst
            <select value={serviceId} onchange={(event) => chooseService((event.currentTarget as HTMLSelectElement).value)}>
              {#each services as option (option.id)}<option value={option.id}>{option.name}</option>{/each}
            </select>
          </label>
          <label>Level der Dokumente
            <select value={String(level)} onchange={(event) => level = Number((event.currentTarget as HTMLSelectElement).value)}>
              {#each service?.levels ?? [] as allowed (allowed)}<option value={String(allowed)}>Level {allowed}</option>{/each}
            </select>
          </label>
        </div>
        {#if service}
          <section class="capacity" class:exhausted={capacity?.exhausted === true} aria-label={`Kontingent von ${service.name}`}>
            {#if !capacity || capacity.reportedAt === null || capacity.exhausted === null}
              <p class="headline">Noch keine Angabe zum Kontingent – der KI-Worker meldet es, sobald er läuft.</p>
            {:else}
              {#if capacity.exhausted}
                <p class="headline" role="status">Kein Kontingent frei – {capacity.availableAgainAt ? `wieder ab ${when(capacity.availableAgainAt)}` : "unbekannt bis wann"}. Neue Dokumente warten bis dahin und starten dann von selbst.</p>
              {:else}
                <p class="headline">Kontingent verfügbar</p>
              {/if}
              <ul>
                {#each capacity.providers as provider (provider.provider)}
                  <li><span class="provider">{providerName(provider.provider)}</span> {providerLine(provider)}</li>
                {/each}
              </ul>
              {#if capacity.stale}
                <p class="stamp">Stand {when(capacity.reportedAt)} – der KI-Worker hat sich seitdem nicht gemeldet.</p>
              {/if}
            {/if}
          </section>
          <p class="hint">Der Text der Dokumente wird an „{service.name}“ übertragen und verarbeitet. Die neuen Notizen bekommen das gewählte Level – „{service.name}“ darf {service.levels.length === 1 ? `nur Level ${service.levels[0]}` : `die Levels ${service.levels.join(", ")}`} verarbeiten.</p>
        {/if}
        <label class="files">Dokumente
          <input type="file" multiple accept=".pdf,.md,.markdown,.txt,application/pdf,text/markdown,text/plain"
            onchange={(event) => choose((event.currentTarget as HTMLInputElement).files)} />
        </label>
        <p class="hint">PDF, Markdown oder Text, bis 20 MB je Datei, höchstens {MAX_FILES} auf einmal.</p>
        {#if rejected.length}<ul class="rejected" role="alert">{#each rejected as problem (problem)}<li>{problem}</li>{/each}</ul>{/if}
        {#if uploadNotice}<p class="notice" role="status">{uploadNotice}</p>{/if}
        {#if files.length}
          <button class="primary" type="submit" disabled={uploading}>{uploading ? "Wird hochgeladen…" : `${plural(files.length, "Dokument", "Dokumente")} einlesen`}</button>
        {/if}
      </form>
    {/if}

    <section class="block">
      <h3>Verarbeitung</h3>
      {#if jobs.length === 0}
        <p class="hint">Noch keine Dokumente eingelesen.</p>
      {:else}
        <ul class="rows">
          {#each jobs as job (job.id)}
            <li class="row">
              <div class="main">
                <span class="name">{job.fileName}</span>
                <span class="meta">{serviceName(job.service)} · Level {job.level} · {when(job.createdAt)}</span>
                {#if jobDetail(job)}<span class="detail" class:problem={job.status === "FAILED"}>{jobDetail(job)}</span>{/if}
              </div>
              <span class="status status-{job.status.toLowerCase()}" class:status-capacity={job.waitingForCapacity && job.status === "PENDING"}>{job.waitingForCapacity && job.status === "PENDING" ? "Wartet auf Kontingent" : STATUS[job.status]}</span>
              {#if (job.status === "PENDING" || job.status === "RUNNING") && canUpload}
                <button class="quiet" aria-label={`${job.fileName} abbrechen`} onclick={() => cancel(job)}>Abbrechen</button>
              {/if}
            </li>
          {/each}
        </ul>
      {/if}
    </section>

    <section class="block">
      <h3>Änderungen der KI</h3>
      {#if report}
        <div class="feedback" role="status">
          <p>{plural(report.result.reverted, "Änderung", "Änderungen")} rückgängig gemacht („{report.label}“).</p>
          {#if report.result.conflicts.length}
            <p>Unverändert geblieben:</p>
            <ul>{#each report.result.conflicts as conflict (conflict.path)}<li>{conflict.path} – {conflict.reason}</li>{/each}</ul>
          {/if}
        </div>
      {/if}
      {#if changeSets.length === 0}
        <p class="hint">Die KI hat in diesem Vault noch nichts geschrieben.</p>
      {:else}
        <ul class="rows">
          {#each changeSets as set (set.id)}
            <li class="row stacked">
              <div class="line">
                <div class="main">
                  <span class="name">{set.label}</span>
                  <span class="meta">{set.agent.replace(/^ki:/, "")} · für {set.requestedBy} · {when(set.createdAt)}</span>
                </div>
                {#if set.revertedAt}
                  <span class="status status-cancelled">Rückgängig gemacht</span>
                {/if}
                <button class="quiet" aria-expanded={openDetail === set.id} aria-label={`Änderungen aus ${set.label} anzeigen`} onclick={() => toggle(set)}>{openDetail === set.id ? "Ausblenden" : "Anzeigen"}</button>
                {#if canRevert && !set.revertedAt}
                  <button class="danger" disabled={reverting !== null} aria-label={`${set.label} rückgängig machen`} onclick={() => revert(set)}>{reverting === set.id ? "Wird rückgängig gemacht…" : "Rückgängig machen"}</button>
                {/if}
              </div>
              {#if openDetail === set.id}
                {#if details[set.id]}
                  {#if details[set.id].changes.length === 0}
                    <p class="hint">Keine Notizen geändert.</p>
                  {:else}
                    <ul class="changes">
                      {#each details[set.id].changes as change (change.noteId + change.at)}
                        <li><span class="kind">{change.kind === "FILE_CREATED" ? "Original" : change.kind === "CREATED" ? "Neu" : "Ergänzt"}</span> {change.path}</li>
                      {/each}
                    </ul>
                  {/if}
                {:else}
                  <p role="status" class="hint">Wird geladen…</p>
                {/if}
              {/if}
            </li>
          {/each}
        </ul>
      {/if}
    </section>
  {/if}
</section>

<style>
  .ai { max-width: 52rem; }
  .intro h2 { font-size: 1.35rem; margin: 0 0 .5rem; }
  .intro p { color: var(--ink-dim); max-width: 62ch; margin: 0 0 2rem; }
  .upload { display: grid; gap: .6rem; padding: 1.25rem 1.5rem; background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius); margin-bottom: 2.5rem; }
  .choices { display: flex; flex-wrap: wrap; gap: 1rem; }
  label { display: grid; gap: .35rem; font-size: .85rem; font-weight: 500; }
  .choices label { flex: 1 1 14rem; }
  .files input { min-height: 48px; padding: .6rem; }
  .upload .hint { margin: 0; }
  .upload .primary { justify-self: start; min-height: 44px; }
  .rejected { margin: 0; padding-left: 1.2rem; color: var(--rust); font-size: .85rem; }
  .notice { margin: 0; color: var(--forest); font-size: .85rem; }
  .block { margin-bottom: 2.5rem; }
  .block h3 { font-size: 1rem; margin: 0 0 .75rem; }
  .rows { list-style: none; margin: 0; padding: 0; border-top: 1px solid var(--line); }
  .row { display: flex; align-items: center; gap: 1rem; padding: .85rem 0; border-bottom: 1px solid var(--line); }
  .row.stacked { display: block; }
  .line { display: flex; align-items: center; gap: 1rem; flex-wrap: wrap; }
  .main { display: grid; gap: .15rem; flex: 1 1 18rem; min-width: 0; }
  .name { font-weight: 600; overflow-wrap: anywhere; }
  .meta, .detail { font-size: .8rem; color: var(--ink-dim); }
  .detail.problem { color: var(--rust); }
  .status { font-size: .75rem; font-weight: 600; letter-spacing: .02em; padding: .2rem .55rem; border-radius: var(--radius); border: 1px solid var(--line); white-space: nowrap; }
  .status-running, .status-pending { color: var(--forest); border-color: var(--forest); }
  .status-succeeded { background: var(--forest-soft); border-color: var(--forest-soft); color: var(--forest); }
  .status-failed { color: var(--rust); border-color: var(--rust); }
  .status-cancelled { color: var(--ink-dim); }
  .changes { margin: .6rem 0 0; padding: 0 0 0 .2rem; list-style: none; display: grid; gap: .3rem; font-size: .85rem; }
  .kind { display: inline-block; min-width: 4.5rem; color: var(--ink-dim); font-size: .75rem; text-transform: uppercase; letter-spacing: .04em; }
  .feedback ul { margin: 0; padding-left: 1.2rem; }
  .capacity { border-left: 3px solid var(--forest); padding: .5rem 0 .5rem .9rem; font-size: .85rem; }
  .capacity.exhausted { border-left-color: var(--rust); }
  .capacity .headline { margin: 0 0 .3rem; font-weight: 600; }
  .capacity.exhausted .headline { color: var(--rust); }
  .capacity ul { margin: 0; padding: 0; list-style: none; display: grid; gap: .2rem; color: var(--ink-dim); }
  .capacity .provider { color: var(--ink); font-weight: 500; margin-right: .35rem; }
  .capacity .stamp { margin: .3rem 0 0; color: var(--ink-dim); font-size: .8rem; }
  .status-capacity { color: var(--rust); border-color: var(--rust); }
</style>
