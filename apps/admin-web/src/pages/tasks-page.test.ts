// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, render, screen } from "@testing-library/vue";
import { afterEach, describe, expect, it, vi } from "vitest";
import { authStore } from "../auth/authStore";
import TasksPage from "./TasksPage.vue";

describe("TasksPage", () => {
  afterEach(() => { cleanup(); authStore.clearSessionForTest(); vi.unstubAllGlobals(); });

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

  it("disables every execution action for a completed task", async () => {
    authStore.setSessionForTest({ accessToken: "dispatcher-token", user: { id: "dispatcher-1", username: "dispatcher01", roles: ["DISPATCHER"], mustChangePassword: false } });
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(completedTaskResponse()));
    render(TasksPage);

    await screen.findByText("COMPLETED");
    for (const name of ["发车", "到站", "上车", "下车", "完成", "车辆故障", "严重延误"]) {
      expect(screen.getByRole("button", { name })).toBeDisabled();
    }
  });
});

function completedTaskResponse(): Response {
  return new Response(JSON.stringify({ data: [{
    id: "task-1",
    vehicleId: "vehicle-1",
    driverId: "driver-1",
    status: "COMPLETED",
    plannedStartAt: "2026-07-13T01:00:00Z",
    stops: [
      { id: "stop-1", virtualStopId: "virtual-stop-1", sequenceNumber: 1, stopType: "BOARDING", plannedArrivalAt: "2026-07-13T01:01:00Z", status: "BOARDED" },
      { id: "stop-2", virtualStopId: "virtual-stop-2", sequenceNumber: 2, stopType: "ALIGHTING", plannedArrivalAt: "2026-07-13T01:11:00Z", status: "ALIGHTED" }
    ]
  }] }), { status: 200, headers: { "Content-Type": "application/json" } });
}
