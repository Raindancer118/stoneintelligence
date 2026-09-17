# StoneIntelligence :: webapp

Web-Dashboard für StoneIntelligence: Login über Authentik (derselbe Public-Client wie das
Obsidian-Plugin), eigene Vaults sehen/anlegen, Rollen/Gruppen/Ordner-ACLs verwalten,
Verbindungskonfiguration fürs Plugin kopieren.

Stack: Vite + Svelte 5 + TypeScript, `oidc-client-ts` für Authorization Code + PKCE im Browser.
Reines SPA (kein eigener Backend-Prozess) - spricht `platform-api` direkt per `fetch` an.

## Entwicklung

```bash
npm install
cp .env.example .env   # ggf. anpassen
npm run dev
```

## Tests & Typecheck

```bash
npm test
npm run check
```

## Deployment

Docker-Multi-Stage-Build (Node-Build → nginx). `.env` ist bewusst committet (kein `.gitignore`-
Eintrag) und wird zur Build-Zeit von Vite eingebettet - enthält keine Secrets, die Client-ID
eines PKCE-Public-Clients ist per Design öffentlich.

```bash
docker build -t stoneintelligence-webapp .
docker run -p 80:80 stoneintelligence-webapp
```

Details zum Live-Deployment (Domain, Reverse Proxy, Firewall): `~/.claude/servers/dorn.md`.
