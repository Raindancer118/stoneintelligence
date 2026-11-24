/** Entkoppelt die API-Schicht von der Oberfläche: 401 mitten in der Sitzung muss die App erfahren. */
type Listener = () => void;
const listeners = new Set<Listener>();

export function onSessionExpired(listener: Listener): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function notifySessionExpired(): void {
  listeners.forEach((listener) => listener());
}
