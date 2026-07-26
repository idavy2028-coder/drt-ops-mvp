// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/vue";
import { afterEach, describe, expect, it } from "vitest";
import DriverCreateForm from "./DriverCreateForm.vue";

describe("DriverCreateForm", () => {
  afterEach(cleanup);

  it("emits a qualified driver payload and preserves optional shift times", async () => {
    const { emitted } = render(DriverCreateForm, { props: { disabled: false, loading: false } });

    await fireEvent.update(screen.getByLabelText("姓名"), "张师傅");
    await fireEvent.update(screen.getByLabelText("手机号"), "13900000001");
    await fireEvent.update(screen.getByLabelText("车队名称"), "通渭县试点车队");
    await fireEvent.update(screen.getByLabelText("班次开始"), "2026-07-22T06:30");
    await fireEvent.update(screen.getByLabelText("班次结束"), "2026-07-22T19:00");
    await fireEvent.click(screen.getByRole("button", { name: "新增驾驶员" }));

    expect(emitted().create).toEqual([[
      {
        name: "张师傅",
        phone: "13900000001",
        qualificationStatus: "QUALIFIED",
        shiftStart: "2026-07-22T06:30:00+08:00",
        shiftEnd: "2026-07-22T19:00:00+08:00",
        currentStatus: "AVAILABLE",
        fleetName: "通渭县试点车队"
      }
    ]]);
  });

  it("blocks submission until required driver fields are complete", async () => {
    render(DriverCreateForm, { props: { disabled: false, loading: false } });

    expect(screen.getByRole("button", { name: "新增驾驶员" })).toBeDisabled();
  });
});
