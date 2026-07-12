// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { render, screen } from "@testing-library/vue";
import { afterEach, describe, expect, it } from "vitest";
import { authStore } from "../auth/authStore";
import OrdersPage from "./OrdersPage.vue";

describe("OrdersPage", () => {
  afterEach(() => authStore.clearSessionForTest());

  it("shows create order action and order status columns", async () => {
    authStore.setSessionForTest({ accessToken: "operator-token", user: { id: "operator-1", username: "operator01", roles: ["OPERATOR"], mustChangePassword: false } });
    render(OrdersPage);

    expect(await screen.findByRole("button", { name: "录入需求" })).toBeInTheDocument();
    expect(screen.getByText("订单状态")).toBeInTheDocument();
    expect(screen.getByText("预计上车时间")).toBeInTheDocument();
  });
});
