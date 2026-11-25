import { describe, expect, it } from "vitest";
import { type AskQuestion } from "../src/lib/api";
import { badge, badgeClass, isOpen, omittedText, renderAnswer, statusText } from "../src/lib/askText";

function question(overrides: Partial<AskQuestion>): AskQuestion {
  return { id: "q1", question: "Wo läuft die Lichtreaktion ab?", service: "gemini", status: "PENDING", progress: null, percent: null,
    error: null, waitingForCapacity: false, availableAt: null, askedAt: "2026-11-25T08:00:00Z", answeredAt: null, answer: null,
    citations: [], restricted: false, omittedForConsent: 0, ...overrides };
}

describe("renderAnswer", () => {
  it("turns citation numbers into links that point at the source", () => {
    const html = renderAnswer("Im Thylakoid [1]. Dazu der Kern [2, 3].");

    const document = new DOMParser().parseFromString(html, "text/html");
    const links = [...document.querySelectorAll("a")].map(a => [a.textContent, a.getAttribute("href")]);
    expect(links).toEqual([["[1]", "#cite-1"], ["[2]", "#cite-2"], ["[3]", "#cite-3"]]);
  });

  it("renders markdown, but never lets model output bring scripts, images, handlers or outside links", () => {
    const html = renderAnswer([
      "**Fett** und eine Liste:", "", "- eins [1]", "- zwei", "",
      "<script>alert(1)</script>", "![x](https://example.test/pixel.png)", "<img src=x onerror=alert(1)>",
      "[Klick](https://example.test/phishing) und [mehr](javascript:alert(1))",
    ].join("\n"));

    expect(html).toContain("<strong>Fett</strong>");
    expect(html).toContain("<li>");
    expect(html).not.toMatch(/<script|<img|onerror|javascript:|example\.test/i);
  });

  it("leaves text without citations alone", () => {
    expect(renderAnswer("Keine Zahl in Klammern hier.")).toContain("Keine Zahl in Klammern hier.");
  });
});

describe("statusText", () => {
  it("says what is going on, in German", () => {
    expect(statusText(question({ status: "PENDING" }))).toBe("Wartet");
    expect(statusText(question({ status: "PENDING", waitingForCapacity: true }))).toBe("Wartet auf Kontingent");
    expect(statusText(question({ status: "RUNNING", progress: "Antwort wird verfasst", percent: 60 }))).toBe("Antwort wird verfasst · 60 %");
    expect(statusText(question({ status: "RUNNING" }))).toBe("Wird beantwortet");
    expect(statusText(question({ status: "FAILED", error: "Modell nicht erreichbar" }))).toBe("Fehlgeschlagen: Modell nicht erreichbar");
    expect(statusText(question({ status: "CANCELLED" }))).toBe("Abgebrochen");
    expect(statusText(question({ status: "ANSWERED" }))).toBe("Beantwortet");
    expect(statusText(question({ status: "NOT_ANSWERABLE" }))).toBe("Nicht beantwortbar");
  });

  it("knows which questions are still open", () => {
    expect(["PENDING", "RUNNING"].map(status => isOpen(question({ status: status as AskQuestion["status"] })))).toEqual([true, true]);
    expect(["ANSWERED", "NOT_ANSWERABLE", "FAILED", "CANCELLED"].map(status => isOpen(question({ status: status as AskQuestion["status"] })))).toEqual([false, false, false, false]);
  });
});

describe("badge", () => {
  it("keeps the label short and picks the shared status style", () => {
    expect(badge(question({ status: "RUNNING", progress: "Antwort wird verfasst", percent: 60 }))).toBe("Wird beantwortet");
    expect(badge(question({ status: "FAILED", error: "Sehr langer Fehler" }))).toBe("Fehlgeschlagen");
    expect(badge(question({ status: "PENDING", waitingForCapacity: true }))).toBe("Wartet auf Kontingent");
    expect(["ANSWERED", "FAILED", "RUNNING", "CANCELLED", "NOT_ANSWERABLE"].map(status => badgeClass(question({ status: status as AskQuestion["status"] }))))
      .toEqual(["status-succeeded", "status-failed", "status-running", "status-cancelled", "status-cancelled"]);
    expect(badgeClass(question({ status: "PENDING", waitingForCapacity: true }))).toBe("status-capacity");
  });
});

describe("omittedText", () => {
  it("tells how many notes were left out for lack of consent", () => {
    expect(omittedText(0)).toBeNull();
    expect(omittedText(1)).toBe("Eine Notiz mit passenden Stellen wurde nicht berücksichtigt, weil ihr Verfasser einer Auswertung durch externe KI-Anbieter nicht zugestimmt hat.");
    expect(omittedText(3)).toBe("3 Notizen mit passenden Stellen wurden nicht berücksichtigt, weil ihre Verfasser einer Auswertung durch externe KI-Anbieter nicht zugestimmt haben.");
  });
});
