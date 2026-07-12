// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { render, screen } from "@testing-library/vue";
import { afterEach, describe, expect, it, vi } from "vitest";
import { authStore } from "../auth/authStore";
import UserManagementPage from "./UserManagementPage.vue";

describe("UserManagementPage", () => {
  afterEach(() => { authStore.clearSessionForTest(); vi.unstubAllGlobals(); });

  it("lists users and exposes account administration actions", async () => {
    authStore.setSessionForTest({ accessToken: "admin-token", user: { id: "admin-1", username: "admin01", roles: ["SYSTEM_ADMIN"], mustChangePassword: false } });
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({ data: [{ id: "dispatcher-1", username: "dispatcher01", displayName: "调度一组", roles: ["DISPATCHER"], enabled: true, mustChangePassword: false }] }), { status: 200, headers: { "Content-Type": "application/json" } })));
    render(UserManagementPage);
    expect(await screen.findByText("dispatcher01")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "新建用户" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "重置密码" })).toBeInTheDocument();
  });
});
