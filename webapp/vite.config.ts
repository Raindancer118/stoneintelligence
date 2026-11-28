import { svelte } from '@sveltejs/vite-plugin-svelte'
import { defineConfig, loadEnv } from 'vite'
import { missingBuildEnv } from './buildEnv.ts'
import { inlineAsset } from './inlineAssets.ts'

// https://vite.dev/config/
export default defineConfig(({ command, mode }) => {
  if (command === 'build') {
    // Die Werte landen fest im Bundle; ohne sie startet die App, aber der Login ist tot.
    const missing = missingBuildEnv({ ...loadEnv(mode, process.cwd(), 'VITE_') })
    if (missing.length > 0) {
      throw new Error(`Build abgebrochen, fehlende Umgebungsvariablen: ${missing.join(', ')} (siehe .env.example)`)
    }
  }
  return {
    plugins: [svelte()],
    build: { assetsInlineLimit: inlineAsset },
  }
})
