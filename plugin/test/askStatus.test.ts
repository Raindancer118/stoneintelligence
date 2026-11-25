import { describe, expect, it } from "vitest";
import { type AskLike, badge, badgeClass, isOpen, omittedText, statusText } from "../src/sync/askStatus";

const question = (overrides: Partial<AskLike>): AskLike => ({
  status: "PENDING", progress: null, percent: null, error: null, waitingForCapacity: false, ...overrides,
});

describe("askStatus", () => {
  it("should_sayWhatIsGoingOn_inGerman", () => {
    expect(statusText(question({ status: "PENDING" }))).toBe("Wartet");
    expect(statusText(question({ status: "PENDING", waitingForCapacity: true }))).toBe("Wartet auf Kontingent");
    expect(statusText(question({ status: "RUNNING", progress: "Antwort wird verfasst", percent: 60 }))).toBe("Antwort wird verfasst · 60 %");
    expect(statusText(question({ status: "RUNNING" }))).toBe("Wird beantwortet");
    expect(statusText(question({ status: "FAILED", error: "Modell nicht erreichbar" }))).toBe("Fehlgeschlagen: Modell nicht erreichbar");
    expect(statusText(question({ status: "CANCELLED" }))).toBe("Abgebrochen");
    expect(statusText(question({ status: "ANSWERED" }))).toBe("Beantwortet");
    expect(statusText(question({ status: "NOT_ANSWERABLE" }))).toBe("Nicht beantwortbar");
  });

  it("should_knowWhichQuestionsAreStillOpen", () => {
    expect((["PENDING", "RUNNING"] as const).map((status) => isOpen(question({ status })))).toEqual([true, true]);
    expect((["ANSWERED", "NOT_ANSWERABLE", "FAILED", "CANCELLED"] as const).map((status) => isOpen(question({ status })))).toEqual([false, false, false, false]);
  });

  it("should_keepTheBadgeShort_andPickTheSharedStyle", () => {
    expect(badge(question({ status: "RUNNING", progress: "Antwort wird verfasst", percent: 60 }))).toBe("Wird beantwortet");
    expect(badge(question({ status: "FAILED", error: "Sehr langer Fehler" }))).toBe("Fehlgeschlagen");
    expect(badge(question({ status: "PENDING", waitingForCapacity: true }))).toBe("Wartet auf Kontingent");
    expect((["ANSWERED", "FAILED", "RUNNING", "CANCELLED", "NOT_ANSWERABLE"] as const).map((status) => badgeClass(question({ status }))))
      .toEqual(["status-succeeded", "status-failed", "status-running", "status-cancelled", "status-cancelled"]);
    expect(badgeClass(question({ status: "PENDING", waitingForCapacity: true }))).toBe("status-capacity");
  });

  it("should_tellHowManyNotesWereLeftOutForLackOfConsent", () => {
    expect(omittedText(0)).toBeNull();
    expect(omittedText(1)).toBe("Eine Notiz mit passenden Stellen wurde nicht berücksichtigt, weil ihr Verfasser einer Auswertung durch externe KI-Anbieter nicht zugestimmt hat.");
    expect(omittedText(3)).toBe("3 Notizen mit passenden Stellen wurden nicht berücksichtigt, weil ihre Verfasser einer Auswertung durch externe KI-Anbieter nicht zugestimmt haben.");
  });
});
