// @vitest-environment jsdom
import { afterEach, describe, expect, it } from "vitest";
import { createMemoryHistory } from "vue-router";
import { authStore } from "../auth/authStore";
import { createAppRouter } from "./index";

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
});
