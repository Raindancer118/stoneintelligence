import { svelte } from '@sveltejs/vite-plugin-svelte'
import { defineConfig } from 'vite'
import { inlineAsset } from './inlineAssets.ts'

// https://vite.dev/config/
export default defineConfig({
  plugins: [svelte()],
  build: { assetsInlineLimit: inlineAsset },
})
