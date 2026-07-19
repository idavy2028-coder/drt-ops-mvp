// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/vue";
import { afterEach, describe, expect, it, vi } from "vitest";
import { authStore } from "../auth/authStore";
import RulesPage from "./RulesPage.vue";

describe("RulesPage", () => {
  afterEach(() => {
    cleanup();
    authStore.clearSessionForTest();
    vi.unstubAllGlobals();
  });

  it("creates the first rule set and selects it for subsequent editing", async () => {
    authStore.setSessionForTest({
      accessToken: "admin-token",
      user: { id: "admin-1", username: "admin", roles: ["SYSTEM_ADMIN"], mustChangePassword: false }
    });
    const created = { id: "rule-1", name: "通渭县试点动态调度规则", ...ruleFields };
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json([]))
      .mockResolvedValueOnce(json(created));
    vi.stubGlobal("fetch", fetchMock);

    render(RulesPage);

    await fireEvent.update(await screen.findByLabelText("规则组名称"), "通渭县试点动态调度规则");
    await fireEvent.click(screen.getByRole("button", { name: "创建规则组" }));

    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      "/api/dispatch-rule-sets",
      expect.objectContaining({ method: "POST" })
    );
    expect(await screen.findByRole("button", { name: "保存规则" })).toBeEnabled();
    expect(screen.getByDisplayValue("5")).toBeInTheDocument();
  });
});

const ruleFields = {
  maxWaitMinutes: 5,
  maxDetourMinutes: 8,
  bookingWindowMinutes: 60,
  autoDispatchScoreThreshold: 82,
  manualReviewScoreThreshold: 62,
  waitWeight: 0.35,
  detourWeight: 0.2,
  stabilityWeight: 0.3,
  utilizationWeight: 0.15,
  insertionPolicy: "REALTIME_INSERTION",
  enabled: true
};

function json(data: unknown): Response {
  return new Response(JSON.stringify({ data }), {
    status: 200,
    headers: { "Content-Type": "application/json" }
  });
}
