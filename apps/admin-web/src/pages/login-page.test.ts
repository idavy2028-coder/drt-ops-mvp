// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/vue";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createMemoryHistory } from "vue-router";
import { authStore } from "../auth/authStore";
import { createAppRouter } from "../router";
import LoginPage from "./LoginPage.vue";

describe("LoginPage", () => {
  afterEach(() => {
    cleanup();
    authStore.clearSessionForTest();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it("401 时清空密码、聚焦密码框并提示检查浏览器保存的旧密码", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 401 })));
    const { router } = await renderLogin();
    const passwordInput = screen.getByLabelText("密码");

    await submitCredentials();

    expect(await screen.findByRole("alert")).toHaveTextContent("LOGIN-401");
    expect(screen.getByRole("alert")).toHaveTextContent("浏览器保存的旧密码");
    expect(passwordInput).toHaveValue("");
    expect(passwordInput).toHaveFocus();
    expect(router.currentRoute.value.name).toBe("login");
  });

  it("403 时提示来源配置错误且保留密码", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 403 })));
    await renderLogin();
    const passwordInput = screen.getByLabelText("密码");

    await submitCredentials();

    expect(await screen.findByRole("alert")).toHaveTextContent("LOGIN-ORIGIN-403");
    expect(screen.getByRole("alert")).toHaveTextContent("当前访问地址未被运营服务允许");
    expect(screen.queryByText("用户名或密码不正确")).not.toBeInTheDocument();
    expect(passwordInput).toHaveValue("TemporaryPassword123!");
  });

  it("网络失败时显示 LOGIN-NETWORK", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("Failed to fetch")));
    await renderLogin();

    await submitCredentials();

    expect(await screen.findByRole("alert")).toHaveTextContent("LOGIN-NETWORK");
    expect(screen.getByRole("alert")).toHaveTextContent("暂时无法连接运营服务");
  });

  it("未知异常时显示 LOGIN-UNKNOWN", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("unexpected")));
    await renderLogin();

    await submitCredentials();

    expect(await screen.findByRole("alert")).toHaveTextContent("LOGIN-UNKNOWN");
    expect(screen.getByRole("alert")).toHaveTextContent("登录失败，请稍后重试");
  });

  it("临时密码账号登录成功后直接请求进入改密页", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(successfulSessionResponse(true)));
    const { router } = await renderLogin();
    const replaceSpy = vi.spyOn(router, "replace");

    await submitCredentials();

    await waitFor(() => expect(replaceSpy).toHaveBeenCalledWith({ name: "changePassword" }));
    expect(router.currentRoute.value.name).toBe("changePassword");
  });

  it("认证成功但导航失败时不误报密码错误", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(successfulSessionResponse(false)));
    const { router } = await renderLogin();
    vi.spyOn(router, "replace").mockRejectedValueOnce(new Error("navigation failed"));

    await submitCredentials();

    expect(await screen.findByRole("alert")).toHaveTextContent("LOGIN-NAVIGATION");
    expect(screen.queryByText("用户名或密码不正确")).not.toBeInTheDocument();
    expect(authStore.authenticated).toBe(true);
  });
});

async function renderLogin() {
  const router = createAppRouter(createMemoryHistory());
  await router.push("/login?redirect=/dispatch");
  await router.isReady();
  render(LoginPage, { global: { plugins: [router] } });
  return { router };
}

async function submitCredentials(password = "TemporaryPassword123!") {
  await fireEvent.update(screen.getByLabelText("用户名"), "admin");
  await fireEvent.update(screen.getByLabelText("密码"), password);
  await fireEvent.click(screen.getByRole("button", { name: "登录" }));
}

function successfulSessionResponse(mustChangePassword: boolean): Response {
  return new Response(JSON.stringify({
    data: {
      accessToken: "test-access-token",
      expiresAt: "2099-01-01T00:00:00Z",
      user: {
        id: "admin-1",
        username: "admin",
        roles: ["SYSTEM_ADMIN"],
        mustChangePassword
      }
    }
  }), {
    status: 200,
    headers: { "Content-Type": "application/json" }
  });
}
