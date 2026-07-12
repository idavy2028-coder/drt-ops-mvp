import { afterEach, describe, expect, it, vi } from "vitest";
import { authStore } from "../auth/authStore";
import { request, unwrapApiResponse } from "./http";

describe("unwrapApiResponse", () => {
  it("returns data from backend response envelope", () => {
    expect(unwrapApiResponse({ data: { id: "1", name: "demo" } })).toEqual({
      id: "1",
      name: "demo"
    });
  });
});

describe("request", () => {
  afterEach(() => {
    authStore.clearSessionForTest();
    vi.unstubAllGlobals();
  });

  it("retries one protected request after refreshing the access token", async () => {
    authStore.setSessionForTest({
      accessToken: "expired-token",
      user: { id: "operator-1", username: "operator01", roles: ["OPERATOR"], mustChangePassword: false }
    });
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response("", { status: 401 }))
      .mockResolvedValueOnce(jsonResponse({
        accessToken: "fresh-token",
        expiresAt: "2026-07-12T16:00:00+08:00",
        user: { id: "operator-1", username: "operator01", roles: ["OPERATOR"], mustChangePassword: false }
      }))
      .mockResolvedValueOnce(jsonResponse([]));
    vi.stubGlobal("fetch", fetchMock);

    await expect(request("/api/orders")).resolves.toEqual([]);
    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(fetchMock.mock.calls[2][1]?.headers).toEqual(expect.any(Headers));
    expect((fetchMock.mock.calls[2][1]?.headers as Headers).get("Authorization")).toBe("Bearer fresh-token");
  });

  it("does not refresh again when the retry remains unauthorized", async () => {
    authStore.setSessionForTest({
      accessToken: "expired-token",
      user: { id: "operator-1", username: "operator01", roles: ["OPERATOR"], mustChangePassword: false }
    });
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response("", { status: 401 }))
      .mockResolvedValueOnce(jsonResponse({
        accessToken: "fresh-token",
        expiresAt: "2026-07-12T16:00:00+08:00",
        user: { id: "operator-1", username: "operator01", roles: ["OPERATOR"], mustChangePassword: false }
      }))
      .mockResolvedValueOnce(new Response("", { status: 401 }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(request("/api/orders")).rejects.toThrow("401");
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });
});

function jsonResponse(data: unknown): Response {
  return new Response(JSON.stringify({ data }), { status: 200, headers: { "Content-Type": "application/json" } });
}
