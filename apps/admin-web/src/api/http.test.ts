import { describe, expect, it } from "vitest";
import { unwrapApiResponse } from "./http";

describe("unwrapApiResponse", () => {
  it("returns data from backend response envelope", () => {
    expect(unwrapApiResponse({ data: { id: "1", name: "demo" } })).toEqual({
      id: "1",
      name: "demo"
    });
  });
});
