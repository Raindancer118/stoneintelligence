<script lang="ts">
  import type { Vault } from "../api";

  let { vaults, signedIn, onLogin, scopedVault = false }: {
    vaults: Vault[]; signedIn: boolean; onLogin: () => void;
    /** Aus einem Vault heraus geöffnet: genau dieser eine Vault, Direkt-Verbinden ganz oben. */
    scopedVault?: boolean;
  } = $props();
  const single = $derived(scopedVault && vaults.length === 1 ? vaults[0] : null);

  // Beide Links sind im Obsidian- bzw. BRAT-Quellcode belegt: "show-plugin" öffnet den
  // Plugin-Browser bei BRAT, "brat?plugin=" öffnet BRATs Installationsdialog vorausgefüllt.
  const BRAT_LINK = "obsidian://show-plugin?id=obsidian42-brat";
  const INSTALL_LINK = `obsidian://brat?plugin=${encodeURIComponent("Raindancer118/stoneintelligence")}`;

  // Nicht "vault=": diesen Parameter liest Obsidian selbst als Namen des zu öffnenden Obsidian-Vaults.
  function connectLink(vault: Vault): string {
    return `obsidian://stoneintelligence-connect?stoneVault=${encodeURIComponent(vault.id)}&name=${encodeURIComponent(vault.name)}`;
  }
</script>

<article class="setup">
  {#if single}
    <h2 class="scoped-title">„{single.name}“ in Obsidian öffnen</h2>
    <div class="quick">
      <div>
        <p class="quick-title">Obsidian und das Plugin hast du schon?</p>
        <p>Ein Klick – das Plugin legt für „{single.name}“ einen eigenen Obsidian-Vault an oder verbindet den geöffneten, wie du möchtest.</p>
      </div>
      <a class="action primary" href={connectLink(single)}>Mit „{single.name}“ verbinden</a>
    </div>
    <p class="lead">Noch nicht eingerichtet? Diese Schritte dauern etwa fünf Minuten – jeder Knopf öffnet Obsidian an der richtigen Stelle.</p>
  {:else}
    <p class="eyebrow">In fünf Minuten startklar</p>
    <h1>Obsidian einrichten</h1>
    <p class="lead">Mit dem StoneIntelligence-Plugin arbeitest du direkt in Obsidian an den gemeinsamen Notizen – live, mit den Cursorn der anderen, auch offline. Jeder Schritt hat einen Knopf, der Obsidian an der richtigen Stelle öffnet.</p>
  {/if}

  <ol class="steps">
    <li>
      <h2>Obsidian installieren</h2>
      <p>Kostenlos für Windows, macOS, Linux, Android und iOS. Hast du Obsidian schon, überspring diesen Schritt.</p>
      <a class="action secondary" href="https://obsidian.md/download" target="_blank" rel="noopener noreferrer">Obsidian herunterladen</a>
    </li>
    <li>
      <h2>Einen Obsidian-Vault öffnen</h2>
      <p>Irgendeinen, auch deinen gewohnten. Beim Verbinden fragt das Plugin, ob der gemeinsame Vault in einen <strong>neuen</strong> Obsidian-Vault kommt – am Computer legt es ihn selbst an – oder in den geöffneten; dessen Notizen würden dann für alle Mitglieder hochgeladen. Auf dem Handy lege vorher selbst einen neuen, leeren Vault an.</p>
    </li>
    <li>
      <h2>BRAT installieren</h2>
      <p>StoneIntelligence wird über das Plugin <em>BRAT</em> verteilt. Fragt Obsidian nach dem eingeschränkten Modus, erlaube Community-Plugins. Dann <em>Installieren</em> und <em>Aktivieren</em>.</p>
      <a class="action secondary" href={BRAT_LINK}>BRAT in Obsidian öffnen</a>
    </li>
    <li>
      <h2>StoneIntelligence installieren</h2>
      <p>Im BRAT-Fenster ist alles vorausgefüllt – nur noch <em>Add plugin</em> bestätigen. Das Plugin wird danach automatisch aktiviert und aktualisiert sich selbst.</p>
      <a class="action primary" href={INSTALL_LINK}>StoneIntelligence installieren</a>
    </li>
    <li>
      <h2>{single ? `Mit „${single.name}“ verbinden` : "Mit deinem Vault verbinden"}</h2>
      {#if !signedIn}
        <p>Nach der Anmeldung bekommst du hier für jeden deiner Vaults einen Knopf, der das Plugin direkt verbindet.</p>
        <button class="action primary" onclick={onLogin}>Anmelden, um deinen Vault zu verbinden</button>
      {:else if vaults.length === 0}
        <p>Du hast noch keinen Vault. Lege im Dashboard einen an oder nimm eine Einladung an – danach steht hier der passende Knopf.</p>
      {:else}
        <p>Das Plugin fragt, in welchen Obsidian-Vault die Notizen sollen, meldet dich an und beginnt mit dem Abgleich.</p>
        <div class="connect">
          {#each vaults as vault (vault.id)}
            <a class="action primary" href={connectLink(vault)}>Mit „{vault.name}“ verbinden</a>
          {/each}
        </div>
      {/if}
    </li>
  </ol>

  <section class="notes">
    <div>
      <h2>Auf dem Handy</h2>
      <p>Öffne diese Seite auf dem Handy und folge denselben Schritten in der Obsidian-App – die Knöpfe funktionieren dort genauso.</p>
    </div>
    <div>
      <h2>Ohne Knöpfe</h2>
      <p>In Obsidian unter <em>Einstellungen → StoneIntelligence</em> anmelden und bei <em>Synchronisieren mit</em> den Vault wählen. Server und Anmeldung sind bereits voreingestellt.</p>
    </div>
  </section>
</article>

<style>
  .setup { max-width: 46rem; }
  .eyebrow { color: var(--forest); font-weight: 700; font-size: .8rem; letter-spacing: .06em; text-transform: uppercase; margin: 0; }
  h1 { font-size: clamp(2.1rem, 5vw, 3rem); letter-spacing: -.035em; line-height: 1.1; margin: .4rem 0 0; }
  .scoped-title { font-size: 1.4rem; margin: 0 0 1rem; }
  .quick { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: center; gap: 1rem; padding: 1.25rem 1.5rem; background: var(--surface); border-radius: var(--radius); box-shadow: 0 1px 3px rgb(33 31 26 / 8%), 0 4px 14px rgb(33 31 26 / 6%); }
  .quick p { margin: 0; color: var(--ink-dim); } .quick .quick-title { color: var(--ink); font-weight: 700; margin-bottom: .2rem; }
  .lead { color: var(--ink-dim); max-width: 58ch; margin: 1.25rem 0 2.5rem; }
  .steps { list-style: none; counter-reset: step; padding: 0; margin: 0; border-top: 1px solid var(--line); }
  .steps li { counter-increment: step; position: relative; padding: 1.5rem 0 1.5rem 3.25rem; border-bottom: 1px solid var(--line); }
  .steps li::before { content: counter(step); position: absolute; left: 0; top: 1.45rem; width: 2rem; height: 2rem; display: grid; place-items: center; border: 1px solid var(--forest); color: var(--forest); border-radius: var(--radius); font-weight: 700; font-size: .9rem; }
  .steps h2, .notes h2 { font-size: 1.05rem; margin: 0 0 .35rem; }
  .steps p { color: var(--ink-dim); margin: 0 0 .9rem; max-width: 60ch; }
  .action { display: inline-flex; align-items: center; min-height: 48px; padding: 0 1rem; text-decoration: none; }
  .connect { display: flex; flex-wrap: wrap; gap: .6rem; }
  .notes { display: grid; grid-template-columns: repeat(auto-fit, minmax(16rem, 1fr)); gap: 2rem; margin-top: 2.5rem; }
  .notes p { color: var(--ink-dim); margin: 0; }
</style>
