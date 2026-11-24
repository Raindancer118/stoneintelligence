/**
 * Wann ein Skeleton sichtbar ist (Hausstil): erst nach `delay` ms, damit schnelle Antworten nicht
 * flackern, und dann mindestens `minVisible` ms, damit er nicht nur aufblitzt.
 */
export class LoadingGate {
  private visible = false;
  private shownAt = 0;
  private timer: ReturnType<typeof setTimeout> | undefined;

  constructor(private readonly onChange: (visible: boolean) => void, private readonly delay = 300,
              private readonly minVisible = 400) {}

  set(loading: boolean) {
    clearTimeout(this.timer);
    if (loading) {
      if (!this.visible) this.timer = setTimeout(() => this.show(true), this.delay);
      return;
    }
    if (!this.visible) return;
    const rest = this.minVisible - (Date.now() - this.shownAt);
    if (rest <= 0) this.show(false);
    else this.timer = setTimeout(() => this.show(false), rest);
  }

  dispose() {
    clearTimeout(this.timer);
  }

  private show(visible: boolean) {
    this.visible = visible;
    if (visible) this.shownAt = Date.now();
    this.onChange(visible);
  }
}
