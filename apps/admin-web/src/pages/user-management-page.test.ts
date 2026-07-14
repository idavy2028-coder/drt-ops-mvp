// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/vue";
import { afterEach, describe, expect, it, vi } from "vitest";
import { authStore } from "../auth/authStore";
import UserManagementPage from "./UserManagementPage.vue";

describe("UserManagementPage", () => {
  afterEach(() => { cleanup(); authStore.clearSessionForTest(); vi.unstubAllGlobals(); });

  it("lists users and exposes account administration actions", async () => {
    authStore.setSessionForTest({ accessToken: "admin-token", user: { id: "admin-1", username: "admin01", roles: ["SYSTEM_ADMIN"], mustChangePassword: false } });
    vi.stubGlobal("fetch", vi.fn().mockImplementation(() => Promise.resolve(usersResponse())));
    render(UserManagementPage);
    expect(await screen.findByText("dispatcher01")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "新建用户" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "重置密码" })).toBeInTheDocument();
  });

  it("sends the administrator supplied temporary password when resetting a user", async () => {
    authStore.setSessionForTest({ accessToken: "admin-token", user: { id: "admin-1", username: "admin01", roles: ["SYSTEM_ADMIN"], mustChangePassword: false } });
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(usersResponse()));
    vi.stubGlobal("fetch", fetchMock);
    render(UserManagementPage);
    await screen.findByText("dispatcher01");

    await fireEvent.update(screen.getByLabelText("dispatcher01 新临时密码"), "Reset456!");
    await fireEvent.click(screen.getByRole("button", { name: "重置密码" }));

    expect(fetchMock).toHaveBeenLastCalledWith(
      "/api/users/dispatcher-1/reset-password",
      expect.objectContaining({ body: JSON.stringify({ temporaryPassword: "Reset456!" }) })
    );
  });
});

function usersResponse(): Response {
  return new Response(JSON.stringify({ data: [{ id: "dispatcher-1", username: "dispatcher01", displayName: "调度一组", roles: ["DISPATCHER"], enabled: true, mustChangePassword: false }] }), { status: 200, headers: { "Content-Type": "application/json" } });
}
