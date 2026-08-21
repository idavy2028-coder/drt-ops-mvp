import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

const appSource = readFileSync(new URL("./App.vue", import.meta.url), "utf8");

describe("管理后台紧凑样式契约", () => {
  it("统一面板间距、卡片内边距和表格行高", () => {
    expect(appSource).toMatch(/\.split-grid\s*{[^}]*gap:\s*12px;/s);
    expect(appSource).toMatch(/\.metric-panel\s*{[^}]*padding:\s*16px;/s);
    expect(appSource).toMatch(/\.work-panel\s*{[^}]*padding:\s*16px;/s);
    expect(appSource).toMatch(
      /\.data-table th,\s*\.data-table td\s*{[^}]*height:\s*44px;[^}]*padding:\s*6px 12px;/s
    );
  });
});
