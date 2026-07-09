// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { render, screen } from "@testing-library/vue";
import { describe, expect, it } from "vitest";
import OrdersPage from "./OrdersPage.vue";

describe("OrdersPage", () => {
  it("shows create order action and order status columns", async () => {
    render(OrdersPage);

    expect(await screen.findByRole("button", { name: "录入需求" })).toBeInTheDocument();
    expect(screen.getByText("订单状态")).toBeInTheDocument();
    expect(screen.getByText("预计上车时间")).toBeInTheDocument();
  });
});
