// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/vue";
import { afterEach, describe, expect, it } from "vitest";
import RecordPagination from "./RecordPagination.vue";

describe("RecordPagination", () => {
  afterEach(cleanup);

  it("显示当前页、总页数和总量，并发出边界内的翻页事件", async () => {
    const view = render(RecordPagination, {
      props: { currentPage: 2, totalItems: 17, pageSize: 8 }
    });

    expect(screen.getByText("第 2 / 3 页 · 共 17 条")).toBeInTheDocument();
    await fireEvent.click(screen.getByRole("button", { name: "下一页" }));
    expect(view.emitted("update:currentPage")).toEqual([[3]]);
  });

  it("空数据时固定为第一页并禁用两个方向", () => {
    render(RecordPagination, {
      props: { currentPage: 1, totalItems: 0, pageSize: 8 }
    });

    expect(screen.getByRole("button", { name: "上一页" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "下一页" })).toBeDisabled();
    expect(screen.getByText("第 1 / 1 页 · 共 0 条")).toBeInTheDocument();
  });
});
