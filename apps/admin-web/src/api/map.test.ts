import { beforeEach, describe, expect, it, vi } from "vitest";

const request = vi.hoisted(() => vi.fn());
vi.mock("./http", () => ({ request }));

import { createServiceArea } from "./map";

describe("service area map API", () => {
  beforeEach(() => request.mockReset());

  it("creates a service area draft with the selected rule set", async () => {
    request.mockResolvedValue({ id: "area-1" });
    const input = {
      name: "通渭县试点服务区",
      boundaryWkt: "POLYGON((105.20 35.18,105.30 35.18,105.30 35.26,105.20 35.18))",
      serviceStart: "06:30:00",
      serviceEnd: "19:00:00",
      ruleSetId: "rule-1"
    };

    await createServiceArea(input);

    expect(request).toHaveBeenCalledWith("/api/service-areas", {
      method: "POST",
      body: JSON.stringify(input)
    });
  });
});
