import { describe, expect, it, vi } from "vitest";
import { onCommentFocus, requestCommentFocus, takeCommentFocus } from "../src/lib/commentFocus";

describe("comment focus", () => {
  it("hands the thread to the matching note once", () => {
    requestCommentFocus("n1", "t1");

    expect(takeCommentFocus("other")).toBeNull();
    expect(takeCommentFocus("n1")).toBe("t1");
    expect(takeCommentFocus("n1")).toBeNull();
  });

  it("tells listeners about a request until they unsubscribe", () => {
    const listener = vi.fn();
    const off = onCommentFocus(listener);

    requestCommentFocus("n2", "t2");
    off();
    requestCommentFocus("n2", "t3");

    expect(listener).toHaveBeenCalledOnce();
    expect(takeCommentFocus("n2")).toBe("t3");
  });
});
