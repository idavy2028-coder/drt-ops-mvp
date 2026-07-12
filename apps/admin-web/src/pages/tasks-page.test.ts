// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { render, screen } from "@testing-library/vue";
import { afterEach, describe, expect, it } from "vitest";
import { authStore } from "../auth/authStore";
import TasksPage from "./TasksPage.vue";

describe("TasksPage", () => {
  afterEach(() => authStore.clearSessionForTest());

  it("shows task execution controls and stop timeline", async () => {
    authStore.setSessionForTest({ accessToken: "dispatcher-token", user: { id: "dispatcher-1", username: "dispatcher01", roles: ["DISPATCHER"], mustChangePassword: false } });
    render(TasksPage);

    expect(await screen.findByRole("button", { name: "发车" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "到站" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "上车" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "下车" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "完成" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "车辆故障" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "严重延误" })).toBeInTheDocument();
    expect(screen.getByText("站点时间线")).toBeInTheDocument();
  });

  it("hides task execution controls from an auditor", async () => {
    authStore.setSessionForTest({ accessToken: "auditor-token", user: { id: "auditor-1", username: "auditor01", roles: ["AUDITOR"], mustChangePassword: false } });
    render(TasksPage);
    const headings = screen.getAllByText("任务执行");
    expect(headings[headings.length - 1]).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "发车" })).not.toBeInTheDocument();
  });
});
