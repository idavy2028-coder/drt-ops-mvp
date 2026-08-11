// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/vue";
import { afterEach, describe, expect, it } from "vitest";
import VehicleLocationSidebar from "./VehicleLocationSidebar.vue";

afterEach(cleanup);

describe("VehicleLocationSidebar", () => {
  it("shows concise vehicle details and emits a selection", async () => {
    const { emitted } = render(VehicleLocationSidebar, {
      props: { locations: [latestLocation()], selectedVehicleId: "vehicle-1" }
    });

    expect(screen.getByText("甘G-T001")).toBeInTheDocument();
    expect(screen.getByText("任务 12345678")).toBeInTheDocument();
    expect(screen.queryByText("12345678-1234-4234-8234-123456789abc")).not.toBeInTheDocument();
    const vehicleButton = screen.getByRole("button", { name: "定位车辆 甘G-T001" });
    expect(vehicleButton).toHaveAttribute("aria-pressed", "true");

    await fireEvent.click(vehicleButton);
    expect(emitted().select).toEqual([["vehicle-1"]]);
  });

  it("sorts active vehicles first and disables vehicles without valid coordinates", () => {
    render(VehicleLocationSidebar, {
      props: {
        locations: [
          latestLocation({ vehicleId: "vehicle-idle", plateNumber: "甘G-IDLE", currentStatus: "IDLE" }),
          latestLocation({ vehicleId: "vehicle-offline", plateNumber: "甘G-OFF", currentStatus: "OFFLINE", longitude: Number.NaN }),
          latestLocation()
        ]
      }
    });

    const buttons = screen.getAllByRole("button", { name: /定位车辆/ });
    expect(buttons.map((button) => button.getAttribute("aria-label"))).toEqual([
      "定位车辆 甘G-T001",
      "定位车辆 甘G-IDLE",
      "定位车辆 甘G-OFF"
    ]);
    expect(screen.getByRole("button", { name: "定位车辆 甘G-OFF" })).toBeDisabled();
    expect(screen.getByText("位置不可用")).toBeInTheDocument();
  });
});

function latestLocation(overrides: {
  vehicleId?: string;
  plateNumber?: string;
  currentStatus?: string;
  longitude?: number;
} = {}) {
  return {
    vehicleId: overrides.vehicleId ?? "vehicle-1",
    plateNumber: overrides.plateNumber ?? "甘G-T001",
    currentStatus: overrides.currentStatus ?? "IN_SERVICE",
    latestLocation: {
      longitude: overrides.longitude ?? 104.6378,
      latitude: 35.2109,
      standardizedAddress: "通渭县客运中心",
      source: "MANUAL_DISPATCHER",
      coordinateSystem: "GCJ02",
      driverReportedAt: "2026-07-13T00:33:00Z",
      recordedAt: "2026-07-13T00:34:00Z",
      eventId: "loc-1",
      vehicleTaskId: "12345678-1234-4234-8234-123456789abc"
    }
  };
}
