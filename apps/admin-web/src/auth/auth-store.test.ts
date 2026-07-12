import { afterEach, describe, expect, it, vi } from "vitest";
import { authStore } from "./authStore";

describe("authStore", () => {
  afterEach(() => {
    authStore.clearSessionForTest();
    vi.unstubAllGlobals();
  });

  it("keeps a successful login session in memory and derives permissions from roles", async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({
      data: {
        accessToken: "access-token",
        expiresAt: "2026-07-12T16:00:00+08:00",
        user: { id: "operator-1", username: "operator01", roles: ["OPERATOR"], mustChangePassword: false }
      }
    }), { status: 200, headers: { "Content-Type": "application/json" } }));
    vi.stubGlobal("fetch", fetchMock);

    await authStore.login("operator01", "Secret123!");

    expect(authStore.accessToken).toBe("access-token");
    expect(authStore.has("ORDER_CREATE")).toBe(true);
    expect(authStore.has("DISPATCH_EXECUTE")).toBe(false);
    expect(fetchMock).toHaveBeenCalledWith("/api/auth/login", expect.objectContaining({ method: "POST" }));
  });
});
