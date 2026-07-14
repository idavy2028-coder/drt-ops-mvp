// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/vue";
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

    await screen.findByText("已完成");
    const selectedRow = screen.getByText("已完成").closest("tr");
    expect(selectedRow).toHaveClass("is-selected");
    expect(screen.getByRole("button", { name: "选择" })).toHaveAttribute("aria-pressed", "true");
    for (const name of ["发车", "到站", "上车", "下车", "完成", "车辆故障", "严重延误"]) {
      expect(screen.getByRole("button", { name })).toBeDisabled();
    }
  });

  it("switches execution context to a non-first dispatched task", async () => {
    authStore.setSessionForTest({ accessToken: "dispatcher-token", user: { id: "dispatcher-1", username: "dispatcher01", roles: ["DISPATCHER"], mustChangePassword: false } });
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(multipleTaskResponse()));
    render(TasksPage);

    const dispatchedTaskRow = (await screen.findByText("待发车")).closest("tr");
    const completedTaskRow = screen.getByText("已完成").closest("tr");
    expect(dispatchedTaskRow).not.toBeNull();
    expect(completedTaskRow).not.toBeNull();

    await fireEvent.click(within(dispatchedTaskRow!).getByRole("button", { name: "选择" }));

    expect(dispatchedTaskRow).toHaveClass("is-selected");
    expect(completedTaskRow).not.toHaveClass("is-selected");
    expect(within(dispatchedTaskRow!).getByRole("button", { name: "选择" })).toHaveAttribute("aria-pressed", "true");
    expect(within(completedTaskRow!).getByRole("button", { name: "选择" })).toHaveAttribute("aria-pressed", "false");
    expect(screen.getByText("计划到站 2026-07-13T02:01:00Z")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "发车" })).toBeEnabled();
    for (const name of ["到站", "上车", "下车", "完成"]) {
      expect(screen.getByRole("button", { name })).toBeDisabled();
    }
  });

  it("starts the selected non-first dispatched task", async () => {
    authStore.setSessionForTest({ accessToken: "dispatcher-token", user: { id: "dispatcher-1", username: "dispatcher01", roles: ["DISPATCHER"], mustChangePassword: false } });
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(multipleTaskResponse())
      .mockResolvedValueOnce(dispatchedTaskStartedResponse());
    vi.stubGlobal("fetch", fetchMock);
    const { container } = render(TasksPage);

    const dispatchedTaskRow = (await screen.findByText("task-dis")).closest("tr");
    expect(dispatchedTaskRow).not.toBeNull();
    const selectionButtons = within(dispatchedTaskRow!).getAllByRole("button");
    expect(selectionButtons).toHaveLength(1);
    await fireEvent.click(selectionButtons[0]);
    const startButton = container.querySelector<HTMLButtonElement>(".toolbar .primary-button");
    expect(startButton).not.toBeNull();
    await fireEvent.click(startButton!);

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
    expect(fetchMock).toHaveBeenLastCalledWith(
      "/api/vehicle-tasks/task-dispatched/start",
      expect.objectContaining({
        method: "POST",
        headers: expect.any(Headers)
      })
    );
    const requestHeaders = fetchMock.mock.calls[1][1]?.headers as Headers;
    expect(requestHeaders.get("Authorization")).toBe("Bearer dispatcher-token");
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

function multipleTaskResponse(): Response {
  return new Response(JSON.stringify({ data: [
    {
      id: "task-completed",
      vehicleId: "vehicle-1",
      driverId: "driver-1",
      status: "COMPLETED",
      plannedStartAt: "2026-07-13T01:00:00Z",
      stops: [
        { id: "completed-boarding", virtualStopId: "virtual-stop-1", sequenceNumber: 1, stopType: "BOARDING", plannedArrivalAt: "2026-07-13T01:01:00Z", status: "BOARDED" },
        { id: "completed-alighting", virtualStopId: "virtual-stop-2", sequenceNumber: 2, stopType: "ALIGHTING", plannedArrivalAt: "2026-07-13T01:11:00Z", status: "ALIGHTED" }
      ]
    },
    {
      id: "task-dispatched",
      vehicleId: "vehicle-2",
      driverId: "driver-2",
      status: "DISPATCHED",
      plannedStartAt: "2026-07-13T02:00:00Z",
      stops: [
        { id: "dispatched-boarding", virtualStopId: "virtual-stop-3", sequenceNumber: 1, stopType: "BOARDING", plannedArrivalAt: "2026-07-13T02:01:00Z", status: "PLANNED" },
        { id: "dispatched-alighting", virtualStopId: "virtual-stop-4", sequenceNumber: 2, stopType: "ALIGHTING", plannedArrivalAt: "2026-07-13T02:11:00Z", status: "PLANNED" }
      ]
    }
  ] }), { status: 200, headers: { "Content-Type": "application/json" } });
}

function dispatchedTaskStartedResponse(): Response {
  return new Response(JSON.stringify({ data: {
    id: "task-dispatched",
    vehicleId: "vehicle-2",
    driverId: "driver-2",
    status: "IN_PROGRESS",
    plannedStartAt: "2026-07-13T02:00:00Z",
    stops: [
      { id: "dispatched-boarding", virtualStopId: "virtual-stop-3", sequenceNumber: 1, stopType: "BOARDING", plannedArrivalAt: "2026-07-13T02:01:00Z", status: "PLANNED" },
      { id: "dispatched-alighting", virtualStopId: "virtual-stop-4", sequenceNumber: 2, stopType: "ALIGHTING", plannedArrivalAt: "2026-07-13T02:11:00Z", status: "PLANNED" }
    ]
  } }), { status: 200, headers: { "Content-Type": "application/json" } });
}
