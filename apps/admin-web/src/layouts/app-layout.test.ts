// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/vue";
import { defineComponent, nextTick } from "vue";
import { createMemoryHistory, createRouter } from "vue-router";
import { afterEach, describe, expect, it } from "vitest";
import { authStore } from "../auth/authStore";
import AppLayout from "./AppLayout.vue";

describe("AppLayout", () => {
  afterEach(() => {
    cleanup();
    authStore.clearSessionForTest();
  });

  it("shows the user management navigation only to a system administrator", () => {
    authStore.setSessionForTest({ accessToken: "admin-token", user: { id: "admin-1", username: "admin01", roles: ["SYSTEM_ADMIN"], mustChangePassword: false } });
    render(AppLayout, { global: { stubs: { RouterLink: { template: "<a href='#'><slot /></a>" }, RouterView: true } } });
    expect(screen.getByRole("link", { name: "用户与权限" })).toBeInTheDocument();
    expect(screen.getByText("admin01")).toBeInTheDocument();
  });

  it("shows the vehicle location history navigation to users with location read permission", () => {
    authStore.setSessionForTest({ accessToken: "dispatcher-token", user: { id: "dispatcher-1", username: "dispatcher01", roles: ["DISPATCHER"], mustChangePassword: false } });
    render(AppLayout, { global: { stubs: { RouterLink: { template: "<a href='#'><slot /></a>" }, RouterView: true } } });

    expect(screen.getByRole("link", { name: "位置历史" })).toBeInTheDocument();
  });

  it("切换菜单后保留缓存页面中的输入状态", async () => {
    const OrdersProbe = defineComponent({
      name: "OrdersProbe",
      template: '<label>筛选条件<input aria-label="筛选条件" /></label>'
    });
    const TasksProbe = defineComponent({
      name: "TasksProbe",
      template: "<p>车辆任务探针</p>"
    });
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: "/orders", name: "orders-probe", component: OrdersProbe, meta: { keepAlive: true } },
        { path: "/tasks", name: "tasks-probe", component: TasksProbe, meta: { keepAlive: true } }
      ]
    });

    await router.push("/orders");
    await router.isReady();
    render(AppLayout, { global: { plugins: [router] } });
    await fireEvent.update(screen.getByRole("textbox", { name: "筛选条件" }), "待调度");

    await router.push("/tasks");
    await nextTick();
    await router.push("/orders");
    await nextTick();

    expect(screen.getByRole("textbox", { name: "筛选条件" })).toHaveValue("待调度");
  });
});
