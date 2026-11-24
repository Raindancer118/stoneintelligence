<script lang="ts">
  import { excerptParts } from "../excerpt";
  let { text }: { text: string } = $props();
</script>
<span class="excerpt">{#each excerptParts(text) as part}{#if part.hit}<mark>{part.text}</mark>{:else}{part.text}{/if}{/each}</span>
