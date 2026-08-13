// @vitest-environment jsdom
import { afterEach, describe, expect, it } from "vitest";
import { createMemoryHistory } from "vue-router";
import { authStore } from "../auth/authStore";
import { createAppRouter, routes } from "./index";

describe("router authentication", () => {
  afterEach(() => {
    authStore.clearSessionForTest();
  });

  it("redirects an unauthenticated visitor to login", async () => {
    const router = createAppRouter(createMemoryHistory());

    await router.push("/dispatch");
    await router.isReady();

    expect(router.currentRoute.value.name).toBe("login");
    expect(router.currentRoute.value.query.redirect).toBe("/dispatch");
  });

  it("redirects an authenticated user without the required permission to the first allowed route", async () => {
    authStore.setSessionForTest({
      accessToken: "operator-token",
      user: { id: "operator-1", username: "operator01", roles: ["OPERATOR"], mustChangePassword: false }
    });
    const router = createAppRouter(createMemoryHistory());

    await router.push("/dispatch");
    await router.isReady();

    expect(router.currentRoute.value.name).toBe("dashboard");
  });

  it("redirects a user who must change password away from every business route", async () => {
    authStore.setSessionForTest({
      accessToken: "temporary-token",
      user: { id: "dispatcher-2", username: "dispatcher02", roles: ["DISPATCHER"], mustChangePassword: true }
    });
    const router = createAppRouter(createMemoryHistory());

    await router.push("/dispatch");
    await router.isReady();

    expect(router.currentRoute.value.name).toBe("changePassword");
  });

  it("allows a user who must change password to visit the password page", async () => {
    authStore.setSessionForTest({
      accessToken: "temporary-token",
      user: { id: "dispatcher-2", username: "dispatcher02", roles: ["DISPATCHER"], mustChangePassword: true }
    });
    const router = createAppRouter(createMemoryHistory());

    await router.push("/change-password");
    await router.isReady();

    expect(router.currentRoute.value.name).toBe("changePassword");
  });

  it("只为订单中心和车辆任务声明页面缓存", () => {
    const cachedRouteNames = routes
      .filter((route) => route.meta?.keepAlive === true)
      .map((route) => route.name);

    expect(cachedRouteNames).toEqual(["orders", "tasks"]);
  });
});
