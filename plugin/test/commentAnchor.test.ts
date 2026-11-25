import { describe, expect, it } from "vitest";
import { findAnchor, makeAnchor, normalize, placeAnchors } from "../src/sync/commentAnchor";

const NOTE = "# Plan\n\nWir starten im **Mai** mit dem Umbau. Danach folgt die Abnahme.\n\n- Budget prüfen\n- Budget prüfen\n";

describe("normalize", () => {
  it("should_dropMarkdownMarkup_andCollapseWhitespace", () => {
    expect(normalize("# Plan\n\nWir **starten** im _Mai_  mit `Code` und [Link](https://x.de) ~~alt~~")).toBe("Plan Wir starten im Mai mit Code und Link alt");
    expect(normalize("- Punkt\n1. Zwei\n> Zitat\n[[Seite|Name]] [[Nur]]")).toBe("Punkt Zwei Zitat Name Nur");
  });

  it("should_keepUnderscoresInsideWords", () => {
    expect(normalize("der snake_case_name bleibt")).toBe("der snake_case_name bleibt");
  });
});

describe("makeAnchor", () => {
  it("should_storeTheQuoteWithContext_notOffsets", () => {
    const anchor = makeAnchor(NOTE, "im Mai mit dem Umbau")!;

    expect(anchor.quote).toBe("im Mai mit dem Umbau");
    expect(anchor.prefix).toBe("Plan Wir starten ");
    expect(anchor.suffix.startsWith(". Danach folgt")).toBe(true);
  });

  it("should_returnNull_forAnEmptySelection", () => {
    expect(makeAnchor(NOTE, "  \n ")).toBeNull();
  });

  it("should_cutVeryLongQuotes", () => {
    expect(makeAnchor("a ".repeat(2000), "a ".repeat(1500))!.quote.length).toBeLessThanOrEqual(1000);
  });

  it("should_pickTheOccurrenceNextToTheHint_whenTheQuoteRepeats", () => {
    const text = "Budget prüfen. Zwischenstück. Budget prüfen.";
    const second = text.lastIndexOf("Budget");

    const anchor = makeAnchor(text, "Budget prüfen", second)!;

    expect(anchor.prefix.endsWith("Zwischenstück. ")).toBe(true);
    expect(findAnchor(text, anchor)).toBe(normalize(text).lastIndexOf("Budget"));
  });

  it("should_keepTheQuote_whenTheSourceDoesNotContainIt", () => {
    expect(makeAnchor("ganz anderer Text", "Fremdes")).toEqual({ quote: "Fremdes", prefix: "", suffix: "" });
  });
});

describe("findAnchor", () => {
  it("should_findTheQuote_afterTextWasInsertedInFront", () => {
    const anchor = makeAnchor(NOTE, "die Abnahme")!;

    expect(findAnchor("Neuer Absatz davor.\n\n" + NOTE, anchor)).toBe(normalize("Neuer Absatz davor.\n\n" + NOTE).indexOf("die Abnahme"));
  });

  it("should_findTheQuote_whenOnlyTheSurroundingsChanged", () => {
    const anchor = makeAnchor(NOTE, "mit dem Umbau")!;

    expect(findAnchor("Wir starten im Juni mit dem Umbau. Ende.", anchor)).toBe(20);
  });

  it("should_prefer_theOccurrenceWithMatchingSurroundings", () => {
    const text = "Alpha Budget prüfen Omega. Beta Budget prüfen Gamma.";
    const anchor = makeAnchor(text, "Budget prüfen", text.lastIndexOf("Budget"))!;

    expect(findAnchor(text, anchor)).toBe(normalize(text).lastIndexOf("Budget"));
  });

  it("should_returnNull_whenTheQuoteIsGone", () => {
    const anchor = makeAnchor(NOTE, "die Abnahme")!;

    expect(findAnchor("Alles neu geschrieben.", anchor)).toBeNull();
  });
});

describe("placeAnchors", () => {
  it("should_orderWholeNoteFirst_thenByPosition_thenLostOnes", () => {
    const items = [
      { id: "gone", anchor: { quote: "weg", prefix: "", suffix: "" } },
      { id: "late", anchor: { quote: "Abnahme", prefix: "", suffix: "" } },
      { id: "note", anchor: null },
      { id: "early", anchor: { quote: "Mai", prefix: "", suffix: "" } },
    ];

    const placed = placeAnchors(NOTE, items);

    expect(placed.map((p) => p.item.id)).toEqual(["note", "early", "late", "gone"]);
    expect(placed.map((p) => p.state)).toEqual(["note", "found", "found", "changed"]);
  });
});
