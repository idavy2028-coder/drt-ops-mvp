import { afterEach, describe, expect, it, vi } from "vitest";
import { authStore } from "../auth/authStore";
import { exportVehicleLocationEvents, listLocationReportVehicles, listVehicleLocationEvents, reportVehicleStandbyLocation } from "./vehicleLocations";

describe("vehicle location API", () => {
  afterEach(() => {
    authStore.clearSessionForTest();
    vi.unstubAllGlobals();
  });

  it("uses backend-supported query params for task history and filters event type and vehicle locally", async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse([
      locationEvent({ id: "event-1", vehicleId: "vehicle-1", eventType: "PASSENGER_BOARDED" }),
      locationEvent({ id: "event-2", vehicleId: "vehicle-1", eventType: "TASK_STARTED" }),
      locationEvent({ id: "event-3", vehicleId: "vehicle-2", eventType: "PASSENGER_BOARDED" })
    ]));
    vi.stubGlobal("fetch", fetchMock);

    const events = await listVehicleLocationEvents({
      vehicleId: "vehicle-1",
      taskId: "task-1",
      eventType: "PASSENGER_BOARDED",
      from: "2026-07-12T16:00:00.000Z",
      to: "2026-07-13T16:00:00.000Z"
    });

    const requestedUrl = new URL(String(fetchMock.mock.calls[0][0]), "http://localhost");
    expect(requestedUrl.pathname).toBe("/api/vehicle-tasks/task-1/location-events");
    expect(requestedUrl.searchParams.get("from")).toBe("2026-07-12T16:00:00.000Z");
    expect(requestedUrl.searchParams.get("to")).toBe("2026-07-13T16:00:00.000Z");
    expect(requestedUrl.searchParams.has("eventType")).toBe(false);
    expect(requestedUrl.searchParams.has("vehicleId")).toBe(false);
    expect(events.map((event) => event.id)).toEqual(["event-1"]);
  });

  it("omits vehicleId from export requests", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response("id\n", { status: 200, headers: { "Content-Type": "text/csv" } }));
    vi.stubGlobal("fetch", fetchMock);

    await exportVehicleLocationEvents({
      vehicleId: "vehicle-1",
      taskId: "task-1",
      eventType: "TASK_STARTED",
      from: "2026-07-12T16:00:00.000Z",
      to: "2026-07-13T16:00:00.000Z"
    });

    const requestedUrl = new URL(String(fetchMock.mock.calls[0][0]), "http://localhost");
    expect(requestedUrl.pathname).toBe("/api/vehicle-locations/export.csv");
    expect(requestedUrl.searchParams.get("taskId")).toBe("task-1");
    expect(requestedUrl.searchParams.get("eventType")).toBe("TASK_STARTED");
    expect(requestedUrl.searchParams.has("vehicleId")).toBe(false);
  });

  it("lists vehicles that can report a standby location", async () => {
    const candidates = [{
      vehicleId: "vehicle-1",
      plateNumber: "甘G12345",
      currentStatus: "IDLE",
      dispatchable: true,
      latestLocation: null
    }];
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(candidates));
    vi.stubGlobal("fetch", fetchMock);

    await expect(listLocationReportVehicles()).resolves.toEqual(candidates);

    const requestedUrl = new URL(String(fetchMock.mock.calls[0][0]), "http://localhost");
    expect(requestedUrl.pathname).toBe("/api/vehicles/location-reporting-candidates");
  });

  it("reports a standby location with the manual-report payload", async () => {
    const response = {
      event: locationEvent({ id: "event-4", vehicleId: "vehicle-1", eventType: "MANUAL_REPORT" }),
      snapshotApplied: true,
      warnings: [],
      replayed: false
    };
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(response));
    vi.stubGlobal("fetch", fetchMock);

    await expect(reportVehicleStandbyLocation("vehicle-1", {
      longitude: 105.2421,
      latitude: 35.2103,
      standardizedAddress: "通渭县客运中心",
      driverReportedAt: "2026-07-26T08:00:00Z",
      idempotencyKey: "report-1",
      virtualStopId: "stop-1",
      note: "待命位置已确认",
      providerDegraded: true,
      outsideServiceArea: true
    })).resolves.toEqual(response);

    const [url, options] = fetchMock.mock.calls[0] as [string, RequestInit];
    const requestedUrl = new URL(String(url), "http://localhost");
    expect(requestedUrl.pathname).toBe("/api/vehicles/vehicle-1/location-reports");
    expect(options.method).toBe("POST");
    expect(JSON.parse(String(options.body))).toEqual({
      vehicleTaskId: null,
      taskStopId: null,
      eventType: "MANUAL_REPORT",
      correctsEventId: null,
      longitude: 105.2421,
      latitude: 35.2103,
      standardizedAddress: "通渭县客运中心",
      driverReportedAt: "2026-07-26T08:00:00Z",
      idempotencyKey: "report-1",
      virtualStopId: "stop-1",
      note: "待命位置已确认"
    });
  });
});

function jsonResponse(data: unknown): Response {
  return new Response(JSON.stringify({ data }), { status: 200, headers: { "Content-Type": "application/json" } });
}

function locationEvent(overrides: { id: string; vehicleId: string; eventType: string }) {
  return {
    id: overrides.id,
    vehicleId: overrides.vehicleId,
    vehicleTaskId: "task-1",
    eventType: overrides.eventType,
    longitude: 104.6378,
    latitude: 35.2109,
    standardizedAddress: "通渭县客运中心",
    source: "MANUAL_DISPATCHER",
    coordinateSystem: "GCJ02",
    driverReportedAt: "2026-07-13T00:33:00Z",
    recordedAt: "2026-07-13T00:36:00Z",
    recordedBy: "dispatcher-1",
    snapshotApplied: true
  };
}
