/**
 * Zaehlt sekundenweise herunter und fuehrt am Ende die Standardwahl aus - genau einmal, und nie
 * mehr, sobald {@link cancel} die andere Wahl getroffen hat.
 */
export class DecisionCountdown {
  private timer: ReturnType<typeof setInterval> | null = null;
  private remaining: number;
  private settled = false;

  constructor(
    private readonly seconds: number,
    private readonly onTick: (secondsLeft: number) => void,
    private readonly onDefault: () => void,
  ) {
    this.remaining = seconds;
  }

  start(): void {
    this.onTick(this.remaining);
    this.timer = setInterval(() => {
      this.remaining -= 1;
      this.onTick(this.remaining);
      if (this.remaining <= 0) {
        this.finishNow();
      }
    }, 1000);
  }

  /** Standardwahl sofort ausfuehren (Knopf gedrueckt oder Dialog geschlossen). */
  finishNow(): void {
    if (this.settled) {
      return;
    }
    this.settled = true;
    this.stop();
    this.onDefault();
  }

  /** Andere Wahl getroffen - die Standardwahl darf nicht mehr passieren. */
  cancel(): void {
    this.settled = true;
    this.stop();
  }

  private stop(): void {
    if (this.timer !== null) {
      clearInterval(this.timer);
      this.timer = null;
    }
  }
}
