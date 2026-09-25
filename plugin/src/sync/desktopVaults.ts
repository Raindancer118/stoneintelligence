import type { LocalVault } from "./localVaultPlan";

/**
 * Obsidians Vault-Verwaltung auf dem Desktop. Obsidian bietet dafuer keine Plugin-API; genutzt
 * werden dieselben IPC-Nachrichten, mit denen Obsidians eigener Vault-Umschalter arbeitet
 * (`vault-list`, `vault-open`), und der localStorage-Schluessel, mit dem Obsidian Community-Plugins
 * pro Vault freigibt (`enable-plugin-<vault-id>`, alle Fenster teilen denselben localStorage).
 * Geprueft gegen Obsidian 1.13 (Ordner-Dialog wie in Obsidians eigenem Vault-Starter); faellt etwas davon weg, liefert `desktopVaults()` null bzw.
 * wirft beim Anlegen - der Aufrufer bietet dann wie bisher nur das Verbinden dieses Vaults an.
 */
export interface DesktopVaults {
  list(): LocalVault[];
  exists(path: string): boolean;
  readPluginData(vaultPath: string, pluginId: string): unknown;
  readPluginFiles(pluginDir: string): Record<string, string>;
  isEmptyFolder(path: string): boolean;
  /** Obsidians nativer Ordner-Dialog (wie beim Anlegen eines Vaults); null = abgebrochen. */
  pickFolder(title: string, defaultPath: string): string | null;
  /** Legt den Ordner mit den Dateien an; existiert er schon, muss er leer sein. */
  createFolder(path: string, files: Array<{ path: string; content: string }>): void;
  /** Oeffnet (und registriert) den Vault in einem eigenen Fenster; Community-Plugins darin freigeben. */
  open(path: string, enablePlugins: boolean): void;
}

const PLUGIN_FILES = ["main.js", "manifest.json", "styles.css"];

export function desktopVaults(): DesktopVaults | null {
  let ipc: { sendSync(channel: string, ...args: unknown[]): unknown };
  let fs: typeof import("fs");
  let nodePath: typeof import("path");
  try {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    ipc = require("electron").ipcRenderer;
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    fs = require("fs");
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    nodePath = require("path");
  } catch {
    return null;
  }
  if (!ipc?.sendSync) {
    return null;
  }

  const list = (): LocalVault[] => {
    const raw = ipc.sendSync("vault-list") as Record<string, { path?: string; open?: boolean }> | null;
    return Object.entries(raw ?? {})
      .filter(([, vault]) => typeof vault?.path === "string")
      .map(([id, vault]) => ({ id, path: nodePath.resolve(vault.path as string), open: vault.open === true }));
  };

  const isEmptyFolder = (path: string): boolean => {
    try {
      return fs.statSync(path).isDirectory() && fs.readdirSync(path).length === 0;
    } catch {
      return false;
    }
  };

  return {
    list,
    exists: (path) => fs.existsSync(path),
    isEmptyFolder,
    pickFolder: (title, defaultPath) => {
      // eslint-disable-next-line @typescript-eslint/no-var-requires
      const { dialog } = require("@electron/remote");
      const picked = dialog.showOpenDialogSync({
        title,
        defaultPath,
        properties: ["openDirectory", "createDirectory", "dontAddToRecent"],
      }) as string[] | undefined;
      return picked && picked.length > 0 ? picked[0] : null;
    },
    readPluginData: (vaultPath, pluginId) => {
      const file = nodePath.join(vaultPath, ".obsidian", "plugins", pluginId, "data.json");
      return fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, "utf8")) : null;
    },
    readPluginFiles: (pluginDir) => {
      const files: Record<string, string> = {};
      for (const name of PLUGIN_FILES) {
        const file = nodePath.join(pluginDir, name);
        if (fs.existsSync(file)) {
          files[name] = fs.readFileSync(file, "utf8");
        }
      }
      if (!files["main.js"] || !files["manifest.json"]) {
        throw new Error("Plugin-Dateien nicht gefunden");
      }
      return files;
    },
    createFolder: (path, files) => {
      if (fs.existsSync(path) && !isEmptyFolder(path)) {
        throw new Error(`„${path}“ existiert bereits und ist nicht leer`);
      }
      fs.mkdirSync(path, { recursive: true });
      for (const file of files) {
        const target = nodePath.join(path, ...file.path.split("/"));
        fs.mkdirSync(nodePath.dirname(target), { recursive: true });
        fs.writeFileSync(target, file.content, "utf8");
      }
    },
    open: (path, enablePlugins) => {
      const resolved = nodePath.resolve(path);
      const result = ipc.sendSync("vault-open", resolved, false);
      if (result !== true) {
        throw new Error(typeof result === "string" && result ? result : "Obsidian konnte den Vault nicht öffnen");
      }
      // Obsidian traegt den Vault synchron in seine Liste ein und laedt das neue Fenster erst danach.
      // Kommt die Freigabe trotzdem zu spaet, fragt Obsidian im neuen Vault einmal selbst nach.
      const id = list().find((vault) => vault.path === resolved)?.id;
      if (enablePlugins && id) {
        window.localStorage.setItem(`enable-plugin-${id}`, "true");
      }
    },
  };
}
