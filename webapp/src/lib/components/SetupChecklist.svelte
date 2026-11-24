<script lang="ts">
  import { dismissSetup, type SetupStep } from "../setupSteps";
  let { vaultId, steps, onStep, onDismiss }: {
    vaultId: string; steps: SetupStep[]; onStep: (id: SetupStep["id"]) => void; onDismiss: () => void;
  } = $props();
  const open = $derived(steps.filter(step => !step.done).length);
</script>
<section class="setup-checklist" aria-labelledby="setup-title">
  <header><h3 id="setup-title">Vault einrichten</h3><span class="hint">{steps.length - open} von {steps.length} erledigt</span></header>
  <ol>
    {#each steps as step (step.id)}
      <li class:done={step.done}>
        <span class="check" aria-hidden="true"></span>
        <div><button class="link" disabled={step.done} onclick={() => onStep(step.id)}>{step.label}</button><span class="hint">{step.done ? "Erledigt" : step.hint}</span></div>
      </li>
    {/each}
  </ol>
  <button class="quiet" onclick={() => { dismissSetup(vaultId); onDismiss(); }}>Ausblenden</button>
</section>
