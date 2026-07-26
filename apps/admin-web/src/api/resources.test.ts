import { beforeEach, describe, expect, it, vi } from "vitest";

const request = vi.hoisted(() => vi.fn());
vi.mock("./http", () => ({ request }));

import { createDriver, createVehicle } from "./resources";

describe("fleet resource API", () => {
  beforeEach(() => request.mockReset());

  it("creates a vehicle with the fleet bootstrap fields", async () => {
    const input = {
      plateNumber: "甘J-DRT01",
      vehicleType: "MINIBUS",
      capacity: 8,
      currentStatus: "IDLE",
      lng: 105.2421,
      lat: 35.2103,
      fleetName: "通渭县试点车队",
      dispatchable: true
    };

    await createVehicle(input);

    expect(request).toHaveBeenCalledWith("/api/vehicles", {
      method: "POST",
      body: JSON.stringify(input)
    });
  });

  it("creates a driver with optional shift times", async () => {
    const input = {
      name: "张师傅",
      phone: "13900000001",
      qualificationStatus: "QUALIFIED",
      shiftStart: "2026-07-22T06:30:00+08:00",
      shiftEnd: "2026-07-22T19:00:00+08:00",
      currentStatus: "AVAILABLE",
      fleetName: "通渭县试点车队"
    };

    await createDriver(input);

    expect(request).toHaveBeenCalledWith("/api/drivers", {
      method: "POST",
      body: JSON.stringify(input)
    });
  });
});
