// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, render, screen } from "@testing-library/vue";
import { afterEach, describe, expect, it } from "vitest";
import VehicleStatusBadge from "./VehicleStatusBadge.vue";

describe("VehicleStatusBadge", () => {
  afterEach(cleanup);

  it.each([
    ["IDLE", "空闲", "status-success"],
    ["AVAILABLE", "空闲", "status-success"],
    ["DISPATCHED", "已派单", "status-active"],
    ["IN_SERVICE", "执行中", "status-active"],
    ["OFFLINE", "离线", "status-danger"],
    ["UNAVAILABLE", "不可用", "status-danger"],
    [undefined, "状态未知", "status-neutral"],
    ["NEW_STATUS", "状态未知", "status-neutral"]
  ])("renders %s as %s", (code, label, tone) => {
    render(VehicleStatusBadge, { props: { code } });

    expect(screen.getByText(label)).toHaveClass(tone);
  });
});
