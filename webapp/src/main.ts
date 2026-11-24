import { mount } from 'svelte'
import './app.css'
import App from './App.svelte'
import { bootTheme } from './lib/theme'

bootTheme()

const app = mount(App, {
  target: document.getElementById('app')!,
})

export default app
