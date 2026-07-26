// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/vue";
import { afterEach, describe, expect, it } from "vitest";
import VehicleCreateForm from "./VehicleCreateForm.vue";

describe("VehicleCreateForm", () => {
  afterEach(cleanup);

  it("emits the trial vehicle payload with practical defaults", async () => {
    const { emitted } = render(VehicleCreateForm, { props: { disabled: false, loading: false } });

    await fireEvent.update(screen.getByLabelText("车牌号"), "甘J-DRT01");
    await fireEvent.update(screen.getByLabelText("车队名称"), "通渭县试点车队");
    await fireEvent.click(screen.getByRole("button", { name: "新增车辆" }));

    expect(emitted().create).toEqual([[
      {
        plateNumber: "甘J-DRT01",
        vehicleType: "MINIBUS",
        capacity: 8,
        currentStatus: "IDLE",
        lng: 105.2421,
        lat: 35.2103,
        fleetName: "通渭县试点车队",
        dispatchable: true
      }
    ]]);
  });

  it("blocks submission when the plate number is blank", async () => {
    render(VehicleCreateForm, { props: { disabled: false, loading: false } });

    expect(screen.getByRole("button", { name: "新增车辆" })).toBeDisabled();
  });
});
