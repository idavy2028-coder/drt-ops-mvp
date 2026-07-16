import { afterEach, describe, expect, it, vi } from "vitest";
import { authStore } from "../auth/authStore";
import { exportVehicleLocationEvents, listVehicleLocationEvents } from "./vehicleLocations";

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
