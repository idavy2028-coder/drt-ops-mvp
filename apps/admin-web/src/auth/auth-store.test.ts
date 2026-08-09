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

  it("changes password with the access token and clears the expired session", async () => {
    authStore.setSessionForTest({
      accessToken: "temporary-token",
      user: { id: "dispatcher-2", username: "dispatcher02", roles: ["DISPATCHER"], mustChangePassword: true }
    });
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetchMock);

    await authStore.changePassword("dispatcher02", "NewDispatcher02!");

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/auth/password",
      expect.objectContaining({
        method: "POST",
        credentials: "include",
        body: JSON.stringify({
          currentPassword: "dispatcher02",
          newPassword: "NewDispatcher02!"
        })
      })
    );
    const headers = fetchMock.mock.calls[0][1].headers as Headers;
    expect(headers.get("Authorization")).toBe("Bearer temporary-token");
    expect(authStore.authenticated).toBe(false);
  });
});
