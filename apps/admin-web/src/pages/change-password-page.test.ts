// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/vue";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createMemoryHistory } from "vue-router";
import { authStore } from "../auth/authStore";
import { createAppRouter } from "../router";
import ChangePasswordPage from "./ChangePasswordPage.vue";

describe("ChangePasswordPage", () => {
  afterEach(() => {
    cleanup();
    authStore.clearSessionForTest();
    vi.unstubAllGlobals();
  });

  it("submits the password change and returns to login", async () => {
    authStore.setSessionForTest({
      accessToken: "temporary-token",
      user: { id: "dispatcher-2", username: "dispatcher02", roles: ["DISPATCHER"], mustChangePassword: true }
    });
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 204 })));
    const router = createAppRouter(createMemoryHistory());
    await router.push("/change-password");
    await router.isReady();
    render(ChangePasswordPage, { global: { plugins: [router] } });

    await fireEvent.update(screen.getByLabelText("当前密码"), "dispatcher02");
    await fireEvent.update(screen.getByLabelText("新密码"), "NewDispatcher02!");
    await fireEvent.update(screen.getByLabelText("确认新密码"), "NewDispatcher02!");
    await fireEvent.click(screen.getByRole("button", { name: "修改密码" }));

    await waitFor(() => expect(router.currentRoute.value.name).toBe("login"));
    expect(authStore.authenticated).toBe(false);
  });
});
