// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { render, screen } from "@testing-library/vue";
import { afterEach, describe, expect, it } from "vitest";
import { authStore } from "../auth/authStore";
import AppLayout from "./AppLayout.vue";

describe("AppLayout", () => {
  afterEach(() => authStore.clearSessionForTest());

  it("shows the user management navigation only to a system administrator", () => {
    authStore.setSessionForTest({ accessToken: "admin-token", user: { id: "admin-1", username: "admin01", roles: ["SYSTEM_ADMIN"], mustChangePassword: false } });
    render(AppLayout, { global: { stubs: { RouterLink: { template: "<a href='#'><slot /></a>" }, RouterView: true } } });
    expect(screen.getByRole("link", { name: "用户与权限" })).toBeInTheDocument();
    expect(screen.getByText("admin01")).toBeInTheDocument();
  });
});
