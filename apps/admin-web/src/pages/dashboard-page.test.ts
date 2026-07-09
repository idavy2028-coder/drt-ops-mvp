// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { render, screen } from "@testing-library/vue";
import { describe, expect, it } from "vitest";
import DashboardPage from "./DashboardPage.vue";

describe("DashboardPage", () => {
  it("renders first-version operations metrics", async () => {
    render(DashboardPage);

    expect(await screen.findByText("订单确认率")).toBeInTheDocument();
    expect(screen.getByText("自动派发率")).toBeInTheDocument();
    expect(screen.getByText("平均等待时间")).toBeInTheDocument();
    expect(screen.getByText("车辆利用率")).toBeInTheDocument();
  });
});
