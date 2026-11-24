<script lang="ts">
  import Icon from "./Icon.svelte";
  import { currentTheme, nextTheme, storeTheme, type Theme } from "../theme";
  let theme = $state<Theme>(currentTheme());
  function toggle() {
    theme = nextTheme(theme);
    storeTheme(theme);
  }
</script>
<button class="theme-toggle quiet" onclick={toggle} aria-label={theme === "dark" ? "Hell darstellen" : "Dunkel darstellen"} title={theme === "dark" ? "Hell" : "Dunkel"}><Icon name={theme === "dark" ? "sun" : "moon"} size={18} /></button>
