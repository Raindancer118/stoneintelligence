import { describe, expect, it } from "vitest";
import { activeMention, insertMention, mentionToken, mentionedSubjects, previewText, segments, suggest } from "../src/sync/commentText";

const members = [
  { subject: "anna", displayName: "Anna Adler" },
  { subject: "ben", displayName: "Ben Berg" },
  { subject: "u-42", displayName: null },
];

describe("segments", () => {
  it("should_splitTextAndMentions_andNeverInterpretMarkup", () => {
    expect(segments("Hi @[Ben Berg](ben), <b>fett</b>?")).toEqual([
      { kind: "text", text: "Hi " },
      { kind: "mention", name: "Ben Berg", subject: "ben" },
      { kind: "text", text: ", <b>fett</b>?" },
    ]);
  });

  it("should_keepBrokenTokensAsText", () => {
    expect(segments("@[Ben]( ben) und @[x")).toEqual([{ kind: "text", text: "@[Ben]( ben) und @[x" }]);
  });
});

describe("mentionToken", () => {
  it("should_buildTheToken_withoutBreakingCharactersInTheName", () => {
    expect(mentionToken("Ben ]Berg\n", "ben")).toBe("@[Ben Berg](ben)");
  });

  it("should_refuseSubjectsThatCannotBeWrittenAsToken", () => {
    expect(mentionToken("X", "a b")).toBeNull();
    expect(mentionToken("X", "a)b")).toBeNull();
  });
});

describe("mentionedSubjects / previewText", () => {
  it("should_listEachPersonOnce", () => {
    expect(mentionedSubjects("@[Ben](ben) @[Anna](anna) @[Ben](ben)")).toEqual(["ben", "anna"]);
  });

  it("should_showMentionsAsAtName", () => {
    expect(previewText("Hallo @[Ben Berg](ben)!")).toBe("Hallo @Ben Berg!");
  });
});

describe("activeMention / suggest / insertMention", () => {
  it("should_recognizeAnAtSignBeforeTheCaret", () => {
    expect(activeMention("Hallo @Be", 9)).toEqual({ start: 6, query: "Be" });
    expect(activeMention("Hallo @", 7)).toEqual({ start: 6, query: "" });
    expect(activeMention("mail a@b.de", 11)).toBeNull();
    expect(activeMention("Hallo @Ben Berg und", 19)).toBeNull();
    expect(activeMention("Hallo @Ben Ber", 14)).toEqual({ start: 6, query: "Ben Ber" });
  });

  it("should_suggestByNamePrefixFirst_thenContains_andHideMyself", () => {
    expect(suggest(members, "be", "anna").map((m) => m.subject)).toEqual(["ben"]);
    expect(suggest(members, "", "anna").map((m) => m.subject)).toEqual(["ben", "u-42"]);
    expect(suggest(members, "rg", null).map((m) => m.subject)).toEqual(["ben"]);
    expect(suggest(members, "zzz", null)).toEqual([]);
  });

  it("should_replaceTheTypedQueryWithTheToken", () => {
    expect(insertMention("Hallo @Be mehr", 6, 9, "Ben Berg", "ben")).toEqual({ text: "Hallo @[Ben Berg](ben) mehr", caret: 23 });
  });
});
