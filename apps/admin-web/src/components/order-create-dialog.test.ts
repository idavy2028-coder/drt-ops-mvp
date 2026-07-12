// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { render, screen } from "@testing-library/vue";
import { describe, expect, it } from "vitest";
import OrderCreateDialog from "./OrderCreateDialog.vue";

describe("OrderCreateDialog", () => {
  it("defaults to coordinates inside the seeded demo service area", () => {
    render(OrderCreateDialog);

    expect(screen.getByLabelText("起点经度")).toHaveValue(116.312);
    expect(screen.getByLabelText("起点纬度")).toHaveValue(39.94);
    expect(screen.getByLabelText("终点经度")).toHaveValue(116.325);
    expect(screen.getByLabelText("终点纬度")).toHaveValue(39.936);
  });
});
