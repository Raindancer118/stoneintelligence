/** Art einer Datei für Liste und Vorschau - allein nach Endung, zur Anzeige. */
export type PreviewKind = "image" | "pdf" | "audio" | "video" | "none";

const KINDS: Record<string, [string, PreviewKind]> = {
  pdf: ["PDF", "pdf"], png: ["Bild", "image"], jpg: ["Bild", "image"], jpeg: ["Bild", "image"], gif: ["Bild", "image"],
  webp: ["Bild", "image"], svg: ["Bild", "image"], avif: ["Bild", "image"], bmp: ["Bild", "image"],
  mp3: ["Audio", "audio"], wav: ["Audio", "audio"], ogg: ["Audio", "audio"], m4a: ["Audio", "audio"], flac: ["Audio", "audio"],
  mp4: ["Video", "video"], webm: ["Video", "video"], mov: ["Video", "video"],
  canvas: ["Canvas", "none"], zip: ["Archiv", "none"], docx: ["Word", "none"], xlsx: ["Excel", "none"], pptx: ["PowerPoint", "none"],
};

function extension(path: string): string {
  const name = path.slice(path.lastIndexOf("/") + 1);
  const dot = name.lastIndexOf(".");
  return dot > 0 ? name.slice(dot + 1).toLowerCase() : "";
}

export function fileKindLabel(path: string): string {
  return KINDS[extension(path)]?.[0] ?? (extension(path).toUpperCase() || "Datei");
}

export function previewKind(path: string): PreviewKind {
  return KINDS[extension(path)]?.[1] ?? "none";
}

export function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${Math.round(bytes / (1024 * 1024))} MB`;
}
