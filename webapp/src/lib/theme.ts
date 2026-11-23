export type Theme = "light" | "dark";
export const THEME_KEY = "stone.theme";

/** Gespeicherte Wahl vor Systemeinstellung. */
export function initialTheme(stored: string | null, prefersDark: boolean): Theme {
  if (stored === "light" || stored === "dark") return stored;
  return prefersDark ? "dark" : "light";
}

export function nextTheme(current: Theme): Theme {
  return current === "dark" ? "light" : "dark";
}

export function applyTheme(theme: Theme) {
  document.documentElement.dataset.theme = theme;
}

export function currentTheme(): Theme {
  return document.documentElement.dataset.theme === "dark" ? "dark" : "light";
}

/** Vor dem ersten Rendern, damit nichts hell aufblitzt. */
export function bootTheme() {
  let stored: string | null = null;
  try { stored = localStorage.getItem(THEME_KEY); } catch { /* privater Modus */ }
  applyTheme(initialTheme(stored, window.matchMedia?.("(prefers-color-scheme: dark)").matches ?? false));
}

export function storeTheme(theme: Theme) {
  applyTheme(theme);
  try { localStorage.setItem(THEME_KEY, theme); } catch { /* privater Modus */ }
}
