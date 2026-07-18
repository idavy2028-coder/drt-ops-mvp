// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/vue";
import { afterEach, describe, expect, it } from "vitest";
import VirtualStopImportPanel from "./VirtualStopImportPanel.vue";

describe("VirtualStopImportPanel", () => {
  afterEach(() => cleanup());

  it("only emits a CSV file after it is selected", async () => {
    const { emitted } = render(VirtualStopImportPanel, { props: { disabled: false, loading: false } });
    const input = screen.getByLabelText("虚拟站点 CSV 文件") as HTMLInputElement;
    const file = new File(["站点名称,地址"], "tongwei-stops.csv", { type: "text/csv" });
    await fireEvent.change(input, { target: { files: [file] } });
    await fireEvent.click(screen.getByRole("button", { name: "导入站点" }));

    expect(emitted().import?.[0]).toEqual([file]);
  });

  it("reports row-level import feedback", () => {
    render(VirtualStopImportPanel, {
      props: { disabled: false, loading: false, result: { createdCount: 2, skippedCount: 1, issues: [{ rowNumber: 4, message: "站点名称已存在" }] } }
    });
    expect(screen.getByText("已创建 2 个站点，跳过 1 行。")).toBeInTheDocument();
    expect(screen.getByText("第 4 行：站点名称已存在")).toBeInTheDocument();
  });
});
