// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { render, screen } from "@testing-library/vue";
import { describe, expect, it } from "vitest";
import TasksPage from "./TasksPage.vue";

describe("TasksPage", () => {
  it("shows task execution controls and stop timeline", async () => {
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
});
