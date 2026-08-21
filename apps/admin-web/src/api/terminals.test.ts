import { beforeEach, describe, expect, it, vi } from "vitest";

const request = vi.hoisted(() => vi.fn());
vi.mock("./http", () => ({ request }));

import { getTerminalDetail, listTerminals, suspendTerminal } from "./terminals";

describe("terminal API", () => {
  beforeEach(() => request.mockReset());

  it("reads the safe terminal summary and detail contracts", async () => {
    await listTerminals();
    await getTerminalDetail("JT-001");

    expect(request).toHaveBeenNthCalledWith(1, "/api/terminals");
    expect(request).toHaveBeenNthCalledWith(2, "/api/terminals/JT-001");
  });

  it("sends the current version and reason for a management action", async () => {
    await suspendTerminal("JT-001", { expectedVersion: 4, reason: "例行检修" });

    expect(request).toHaveBeenCalledWith("/api/terminals/JT-001/suspend", {
      method: "POST",
      body: JSON.stringify({ expectedVersion: 4, reason: "例行检修" })
    });
  });
});
