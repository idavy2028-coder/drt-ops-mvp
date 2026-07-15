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

  it("opens a location confirmation panel instead of directly starting the selected task", async () => {
    authStore.setSessionForTest({ accessToken: "dispatcher-token", user: { id: "dispatcher-1", username: "dispatcher01", roles: ["DISPATCHER"], mustChangePassword: false } });
    const fetchMock = taskPageFetchMock();
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

    expect(await screen.findByText("确认发车位置")).toBeInTheDocument();
    expect(fetchMock.mock.calls.some(([url]) => String(url).includes("/api/vehicle-tasks/task-dispatched/start"))).toBe(false);
  });

  it("submits a task action with the confirmed location report and updates from response.task", async () => {
    authStore.setSessionForTest({ accessToken: "dispatcher-token", user: { id: "dispatcher-1", username: "dispatcher01", roles: ["DISPATCHER"], mustChangePassword: false } });
    vi.spyOn(crypto, "randomUUID").mockReturnValue("22222222-2222-4222-8222-222222222222");
    const fetchMock = taskPageFetchMock();
    vi.stubGlobal("fetch", fetchMock);
    const { container } = render(TasksPage);

    const dispatchedTaskRow = (await screen.findByText("task-dis")).closest("tr");
    expect(dispatchedTaskRow).not.toBeNull();
    await fireEvent.click(within(dispatchedTaskRow!).getByRole("button", { name: "选择" }));
    const startButton = container.querySelector<HTMLButtonElement>(".toolbar .primary-button");
    expect(startButton).not.toBeNull();
    await fireEvent.click(startButton!);

    await fireEvent.update(await screen.findByLabelText("经度"), "104.6378");
    await fireEvent.update(screen.getByLabelText("纬度"), "35.2109");
    await fireEvent.update(screen.getByLabelText("标准化地址"), "通渭县客运中心");
    await fireEvent.update(screen.getByLabelText("驾驶员反馈时间"), "2026-07-13T02:00");
    await fireEvent.click(screen.getByRole("button", { name: "确认发车" }));

    await waitFor(() => expect(fetchMock.mock.calls.some(([url]) => String(url).includes("/api/vehicle-tasks/task-dispatched/start"))).toBe(true));
    const startCall = fetchMock.mock.calls.find(([url]) => String(url).includes("/api/vehicle-tasks/task-dispatched/start"));
    expect(startCall).toEqual([
      "/api/vehicle-tasks/task-dispatched/start",
      expect.objectContaining({
        method: "POST",
        headers: expect.any(Headers)
      })
    ]);
    const startOptions = startCall?.[1] as RequestInit;
    expect(JSON.parse(startOptions.body as string)).toEqual({
      locationReport: {
        longitude: 104.6378,
        latitude: 35.2109,
        standardizedAddress: "通渭县客运中心",
        driverReportedAt: "2026-07-12T18:00:00.000Z",
        idempotencyKey: "22222222-2222-4222-8222-222222222222"
      }
    });
    const requestHeaders = startOptions.headers as Headers;
    expect(requestHeaders.get("Authorization")).toBe("Bearer dispatcher-token");
    expect(await screen.findByText("执行中")).toBeInTheDocument();
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

function emptyListResponse(): Response {
  return new Response(JSON.stringify({ data: [] }), { status: 200, headers: { "Content-Type": "application/json" } });
}

function taskPageFetchMock() {
  return vi.fn((url: string, _options?: RequestInit) => {
    if (url === "/api/vehicle-tasks") {
      return Promise.resolve(multipleTaskResponse());
    }
    if (url === "/api/virtual-stops" || url === "/api/vehicles/locations/latest") {
      return Promise.resolve(emptyListResponse());
    }
    if (url === "/api/vehicle-tasks/task-dispatched/start") {
      return Promise.resolve(dispatchedTaskStartedActionResponse());
    }
    return Promise.resolve(emptyListResponse());
  });
}

function dispatchedTaskStartedActionResponse(): Response {
  return new Response(JSON.stringify({ data: {
    task: {
      id: "task-dispatched",
      vehicleId: "vehicle-2",
      driverId: "driver-2",
      status: "IN_PROGRESS",
      plannedStartAt: "2026-07-13T02:00:00Z",
      stops: [
        { id: "dispatched-boarding", virtualStopId: "virtual-stop-3", sequenceNumber: 1, stopType: "BOARDING", plannedArrivalAt: "2026-07-13T02:01:00Z", status: "PLANNED" },
        { id: "dispatched-alighting", virtualStopId: "virtual-stop-4", sequenceNumber: 2, stopType: "ALIGHTING", plannedArrivalAt: "2026-07-13T02:11:00Z", status: "PLANNED" }
      ]
    },
    locationEvent: {
      id: "loc-1",
      vehicleId: "vehicle-2",
      vehicleTaskId: "task-dispatched",
      eventType: "TASK_STARTED",
      longitude: 104.6378,
      latitude: 35.2109,
      standardizedAddress: "通渭县客运中心",
      source: "MANUAL_DISPATCHER",
      coordinateSystem: "GCJ02",
      driverReportedAt: "2026-07-13T02:00:00Z",
      recordedAt: "2026-07-13T02:00:05Z",
      recordedBy: "dispatcher-1",
      snapshotApplied: true
    },
    snapshotApplied: true,
    warnings: [],
    replayed: false
  } }), { status: 200, headers: { "Content-Type": "application/json" } });
}
